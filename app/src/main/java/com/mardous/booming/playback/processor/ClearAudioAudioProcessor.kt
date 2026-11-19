package com.mardous.booming.playback.processor

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.tarvos.clearaudio.DsxConfig
import com.tarvos.clearaudio.DsxSimulator
import com.tarvos.clearaudio.Eq10Channel
import com.tarvos.clearaudio.Eq10Tables
import com.tarvos.clearaudio.FullChainChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Status des ClearAudio+-Processors fuer die UI.
 *
 * [enabled] = Master-Schalter-Stellung (Nutzer-Setting),
 * [activeInChain] = der Processor verarbeitet tatsächlich im Moment (Format
 * unterstützt: 44.1 oder 48 kHz, 1-2 Kanäle, PCM16/Float). [sampleRate]/
 * [channelCount] beschreiben das EINGANGS-Format, das der Processor zuletzt
 * gesehen hat (0 = noch kein Stream). Die Ausgabe läuft mit der doppelten
 * Rate (88.2/96 kHz), siehe [ClearAudioAudioProcessor].
 */
data class ClearAudioStatus(
    val enabled: Boolean,
    val activeInChain: Boolean,
    val sampleRate: Int,
    val channelCount: Int,
    /** Input-Encoding des zuletzt gesehenen Streams (C.ENCODING_*), Diagnose. */
    val encoding: Int = C.ENCODING_INVALID
)

/**
 * ClearAudio+ (DSEE AI) Nachbau, Phase 3f - FULL-CHAIN: statt der
 * getrennten dry/wet-Rekonstruktion laeuft hier die KOMPLETTE im Emulator
 * rekonstruierte 0x129/0x12a/0x12c-Kette als 1:1-Port, bitweise abgenommen
 * gegen die 16-Block-Emulator-Referenz (KNOWLEDGE 289/290: FAIL=0, ok=4066,
 * dust=30, maxDust 2.6e-35; JVM-Abnahme der ausgelieferten Dateien erneut
 * gruen):
 *
 *   64er-Block je Kanal -> In-Bank(hist24++fresh64++win20)
 *      -> Stage A (25er-Polyphase-2x-FIR)     -> fin[62..190)
 *      -> Analyse (Gain -> DC-Notch -> D-FIR) -> D[16..32)
 *      -> Upcon P0 (1*FIN_old + 8*lane) -> Upcon P1 (P1-Taps)
 *      -> Ausgabe fin[0..128) = 128 Werte @2x Rate
 *
 * Alle Arithmetik in Kotlin-Float (= IEEE f32 je Operation, kein FMA), die
 * Op-Reihenfolge des Firmware-DSP ist exakt erhalten. Der 0x115-Driver-
 * Staub D[32..52) ist als konstante 0 kodiert (Werte <= 2.7e-35,
 * PCM16-wirkungslos, KNOWLEDGE 289). Der Streaming-State (124 f32 je Kanal
 * + FIN-Rotationsrest) laeuft ueber Block- und Trackgrenzen weiter wie im
 * Original; onReset stellt den Kaltstart (alles 0) wieder her.
 *
 * Aktiv bei 48 kHz und 44.1 kHz, 1-2 Kanäle, PCM16/Float:
 *  - 48 kHz: volle Chain (Phase 3f, bit-exakt, oben beschrieben)
 *  - 44.1 kHz: die in Phase 3e gemessene und validierte Architektur
 *    (Site-1-Fullband-Upsampling + GUCF-Wet), bis eine eigene 44.1-kHz-
 *    Full-Chain-Emulator-Referenz vorliegt (die Chain-Koeffizienten
 *    K65/Taps/RODATA sind die gemessene 48-kHz-Konfiguration).
 * Alles andere wird unangetastet durchgereicht (NOT_SET-Bypass, kein Werfen
 * einer UnhandledAudioFormatException, da die DefaultAudioSink daraus eine
 * ConfigurationException macht, die die Wiedergabe komplett stoppt).
 *
 * Ist der Schalter beim Configure aus, gibt der Processor NOT_SET zurueck
 * (1x-Passthrough, keine Kosten). Wird der Schalter mitten im Stream abge-
 * schaltet, bleibt das Ausgabeformat 2x (Formatwechsel nur per onConfigure):
 * dann laeuft das reine Site-1-Upsampling des Phase-3e-Stacks ohne
 * Effektanteil (Full-Chain-Kanaele halten dabei ihren Zustand und uebernehmen
 * beim naechsten 256er-Frame nach dem Einschalten wieder nahtlos).
 *
 * Verarbeitet in 256er-Frames (= 4 Full-Chain-64er-Bloecke je Kanal) mit
 * Zero-Pad-Flush am Streamende; alle Bloecke eines queueInput-Aufrufs
 * werden in EINEN Output-Buffer geschrieben (BaseAudioProcessor.
 * replaceOutputBuffer ueberschreibt noch nicht abgeholte Buffer
 * kommentarlos - Bugfix aus Phase 3d).
 */
@OptIn(UnstableApi::class)
class ClearAudioAudioProcessor : BaseAudioProcessor() {

    data class Config(
        val sampleRate: Int = 48000,
        val channels: Int = 2,
        val floatIn: Boolean = false
    )

    private val _status = MutableStateFlow(ClearAudioStatus(false, false, 0, 0))

    /** Live-Status fuer die UI (EqualizerScreen). */
    val status: StateFlow<ClearAudioStatus> = _status

    @Volatile
    private var enabled = false

    private var sampleRate = 0
    private var channels = 0
    private var floatIn = false

    private var simulator: DsxSimulator? = null          // Site-1-Pfad (mid-stream off)
    private var simConfig: Config? = null
    private var fullCh: Array<FullChainChannel>? = null    // Full-Chain-Pfad (on)
    private var eqCh: Array<Eq10Channel>? = null            // Eq10 UNIQUE je Kanal (on)
    private var pending: Array<FloatArray>? = null         // je Kanal bis 255 Samples (f32)
    private var pendingCount = 0

    // Full-Chain-Scratch (Wiederverwendung, keine Allokation im Frame)
    private val fFresh = FloatArray(FULL_BLOCK)
    private val fOut = FloatArray(2 * FULL_BLOCK)

    /** Master-Schalter (Settings/UI). Bei false mid-stream: Site-1-Upsampling. */
    @Synchronized
    fun setEnabled(value: Boolean) {
        enabled = value
        _status.value = _status.value.copy(enabled = value)
    }

    /**
     * Anti-Clip-Schutz v2 (KNOWLEDGE 297-299): die Original-Kette liefert bei
     * HF-reichem Material Spitzen bis +31 dB (Bode 14-18 kHz Faktor x35), der
     * Chain-HF-Synthese-Pegel liegt bis +22 dB ueber dem Bypass. Die v1-
     * Variante (-9 dB + Softknee auf alles) stauchte aber messbar (err
     * 3.1 %, hörbares Knistern bei Becken/Hi-Hats, KNOWLEDGE 299). v2
     * zaehmt deshalb NUR den HF-Anteil adaptiv statt alles zusammen:
     *
     *   x = v * -9 dB
     *   Split @ 9 kHz (LP 1. Ordnung, Ausgrate 2x sr)
     *   hf-Env = Peak-Detektor (Attack 10 ms, Release 150 ms) auf den
     *            HF-Anteil; g = min(1, T/env) mit T = -31 dBFS
     *   y = Softknee(lp + g*hf), Knie 0.9, Asymptote 1.0
     *
     * Damit greift das Knie praktisch nie (Referenzmessung am Prodigy-
     * Track: sat-Wirkzeit 0.01 %, Clipping 0.00 %), und die leisen Passagen
     * behalten den vollen HF-Boost. Referenz hfRMS -25.8 dBFS zwischen den
     * Dennis genehmen Varianten 05/06 (-25.0) und der sauberen -18-dB-
     * Referenz (-27.9).
     */
    @Volatile
    private var antiClip = true

    /** v2-State je Kanal: LP-Split und HF-Envelope (0 = noch nicht konfiguriert). */
    private var acLp = FloatArray(0)
    private var acEnv = FloatArray(0)

    /** v2-Koeffizienten (von der Ausgabeabtastrate 2x sr abhaengig, in onConfigure). */
    private var acLpA = 0f
    private var acAtt = 0f
    private var acRel = 0f

    @Synchronized
    fun setAntiClip(value: Boolean) {
        antiClip = value
    }

    /** State/Koeffizienten fuer ein neues Ausgabeformat (Ausgrate 2*sr, [nch] Kanäle). */
    private fun initAntiClipState(outRate: Int, nch: Int) {
        acLp = FloatArray(nch)
        acEnv = FloatArray(nch)
        acLpA = (1.0 - exp(-2.0 * Math.PI * 9000.0 / outRate)).toFloat()
        acAtt = exp(-1.0 / (0.010 * outRate)).toFloat()
        acRel = exp(-1.0 / (0.150 * outRate)).toFloat()
    }

    private fun releaseAntiClipState() {
        acLp = FloatArray(0)
        acEnv = FloatArray(0)
    }

    /**
     * Anti-Clip v2 je Sample (siehe [antiClip]): -9 dB, Split @9 kHz,
     * adaptiver HF-Gain gegen den Env-Deckel T=-31 dBFS, dann das
     * vertraute saturierende Knie (unter 0.9 identisch mit der Chain).
     */
    private fun applyAntiClip(ch: Int, v: Float): Float {
        val x = v * ANTI_CLIP_SCALE
        val lpS = acLp[ch]
        val lp = lpS + acLpA * (x - lpS)
        acLp[ch] = lp
        val hf = x - lp
        val a = abs(hf)
        val e = acEnv[ch]
        val env = if (a > e) acAtt * e + (1f - acAtt) * a else acRel * e + (1f - acRel) * a
        acEnv[ch] = env
        val g = min(1f, ANTI_CLIP_T / max(1e-9f, env))
        val y = lp + g * hf
        if (y > ANTI_CLIP_KNEE) {
            val ex = y - ANTI_CLIP_KNEE
            return ANTI_CLIP_KNEE + (1f - ANTI_CLIP_KNEE) * (1f - exp(-ex * ANTI_CURVE))
        }
        if (y < -ANTI_CLIP_KNEE) {
            val ex = -y - ANTI_CLIP_KNEE
            return -(ANTI_CLIP_KNEE + (1f - ANTI_CLIP_KNEE) * (1f - exp(-ex * ANTI_CURVE)))
        }
        return y
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val sr = inputAudioFormat.sampleRate
        // Phase 3f.1: 48 kHz = Full-Chain; 44.1 kHz = Phase-3e-Wet-Architektur.
        val ok = (sr == 48000 || sr == 44100) &&
            (inputAudioFormat.channelCount == 1 || inputAudioFormat.channelCount == 2) &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        if (!ok || !enabled) {
            // Bypass: NOT_SET signalisiert der Pipeline, diesen Processor zu
            // ueberbruecken. NIEMALS UnhandledAudioFormatException werfen.
            sampleRate = if (ok) sr else 0
            channels = if (ok) inputAudioFormat.channelCount else 0
            floatIn = false
            if (!ok) {
                simulator = null
                simConfig = null
                fullCh = null
                eqCh = null
                pending = null
                pendingCount = 0
                releaseAntiClipState()
            }
            // Diagnose: auch im Bypass das GESEHENE Format melden (der
            // Status kennt kHz/Kanaele/Encoding, damit die UI sagen kann,
            // WARUM gebypasst wird). Interne Member bleiben bei !ok null.
            _status.value = _status.value.copy(
                activeInChain = false,
                sampleRate = sr,
                channelCount = inputAudioFormat.channelCount,
                encoding = inputAudioFormat.encoding
            )
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = sr
        channels = inputAudioFormat.channelCount
        floatIn = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        initAntiClipState(2 * sr, channels)
        _status.value = _status.value.copy(
            activeInChain = true, sampleRate = sr, channelCount = channels,
            encoding = inputAudioFormat.encoding
        )
        // Full-Chain: je 64er-Eingabeblock 128 Ausgangswerte @2x Rate.
        // Ausgabeformat = doppelte Rate, Kanalzahl/Encoding unveraendert.
        return AudioProcessor.AudioFormat(
            2 * sr, channels, inputAudioFormat.encoding
        )
    }

    override fun onFlush() {
        // angebrochene Frames verwerfen, DSP-Zustaende aber NICHT anfassen:
        // der Effekt soll ueber Track-Grenzen hinweg weiterlaufen wie im Original.
        pending = null
        pendingCount = 0
    }

    override fun onReset() {
        simulator = null
        simConfig = null
        fullCh = null
        eqCh = null
        pending = null
        pendingCount = 0
        releaseAntiClipState()
    }

    override fun onQueueEndOfStream() {
        val sim = simulator
        val pend = pending
        if (sim != null && pend != null && pendingCount > 0) {
            // angebrochenen Frame mit Nullen auffuellen und verarbeiten (Firmware-Flush)
            val frame = Array(channels) { ch ->
                FloatArray(FRAME) { if (it < pendingCount) pend[ch][it] else 0f }
            }
            emitFrames(listOf(processFrame(sim, frame)))
        }
        pending = null
        pendingCount = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // Media3 liefert 16-bit-PCM/Float als Little-Endian im Buffer; das
        // explizit setzen macht den Processor unabhaengig von der Byte-Order,
        // mit der der Buffer erzeugt wurde.
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        val cfg = Config(sampleRate, channels, floatIn)
        var sim = simulator
        if (sim == null || simConfig != cfg) {
            // Stream-Format hat sich geaendert: Site-1-Stack + Full-Chain-Kanaele
            // mit neuer Konfiguration neu aufbauen (Kaltstart = alles 0).
            sim = DsxSimulator(DsxConfig(ch = channels, sr = sampleRate)).also {
                simulator = it
                simConfig = cfg
                fullCh = Array(channels) { FullChainChannel(sampleRate == 44100) }
                eqCh = Array(channels) { Eq10Channel(eq10Coeffs()) }
                pending = null
                pendingCount = 0
            }
        }

        val outBlocks = ArrayList<Array<FloatArray>>()
        val bytesPerFrame = channels * (if (floatIn) 4 else 2)
        while (inputBuffer.remaining() >= bytesPerFrame) {
            val pend = pending ?: Array(channels) { FloatArray(FRAME) }.also { pending = it }
            for (ch in 0 until channels) {
                // f32-Exaktheit: PCM16/32768 ist exakt in Float darstellbar,
                // Float-Input wird 1:1 durchgereicht (decode-faithful).
                pend[ch][pendingCount] =
                    if (floatIn) Float.fromBits(inputBuffer.int)
                    else inputBuffer.short / 32768f
            }
            pendingCount++
            if (pendingCount == FRAME) {
                outBlocks.add(processFrame(sim, pend))
                pendingCount = 0
            }
        }
        if (outBlocks.isNotEmpty()) {
            emitFrames(outBlocks)
        }
    }

    /**
     * Ein 256er-Eingabeframe -> 512 Ausgangswerte je Kanal @2x Rate
     * (48 kHz UND 44.1 kHz, Tabellenwahl per FullChainChannel-Flag).
     * An (44.1 und 48 kHz): Full-Chain (rate-spezifische Tabellen); aus:
     * Site-1. Der Phase-3e-Wet-Pfad faellt damit weg (44.1-Luecke 293).
     * Das Ausgabeformat bleibt in allen Faellen 2x (siehe KDoc der Klasse).
     */
    private fun processFrame(sim: DsxSimulator, frame: Array<FloatArray>): Array<FloatArray> {
        return when {
            enabled && (sampleRate == 48000 || sampleRate == 44100) ->
                // Beide Raten: bit-genauer Full-Chain-Port (48k: KNOWLEDGE
                // 289/291; 44.1k: 293, K65/TAPS/SA-Taps aus FC44).
                processFrameFull(frame)
            enabled -> {
                // 44.1 kHz: Phase-3e-Architektur (Site-1-Upsampling + GUCF-Wet),
                // 40/40 Harness-Checks, Image-Rejection ~47 dB.
                val dbl = Array(frame.size) { ch ->
                    DoubleArray(frame[ch].size) { frame[ch][it].toDouble() }
                }
                val (dry, wet) = sim.executeFrameDryWet(dbl)
                Array(frame.size) { ch ->
                    val d = dry[ch]
                    val w = wet[ch]
                    FloatArray(d.size) { i -> (d[i] + w[i]).toFloat() }
                }
            }
            else -> {
                // Mid-stream aus: bewaehrtes Site-1-Upsampling der Phase 3e
                // (Firmware-Fenstertabelle, Image-Rejection ~47 dB).
                val dbl = Array(frame.size) { ch ->
                    DoubleArray(frame[ch].size) { frame[ch][it].toDouble() }
                }
                sim.executeFrameUpsample2x(dbl).map { arr ->
                    FloatArray(arr.size) { arr[it].toFloat() }
                }.toTypedArray()
            }
        }
    }

    /**
     * Full-Chain-Pfad: je Kanal 4x 64er-Bloecke durch die Kette, Ausgabe
     * 4x128 = 512 Werte @2x. Der Kanal-State (hist/win/or notch u,v/D/
     * FIN-Rotation) sitzt in FullChainChannel und traegt ueber Frames fort.
     * Danach Eq10 UNIQUE (KNOWLEDGE 303): 8x 64er-Bloecke des 2x-Ausgangs,
     * Biquad-Zustaende fortlaufend (Original: Dsx -> Eq10 -> [Heq] -> Vpt).
     */
    private fun processFrameFull(frame: Array<FloatArray>): Array<FloatArray> {
        val chans = fullCh
            ?: Array(frame.size) { FullChainChannel(sampleRate == 44100) }
        val eqs = eqCh
            ?: Array(frame.size) { Eq10Channel(eq10Coeffs()) }
        val out = Array(frame.size) { FloatArray(2 * FRAME) }
        for (ch in frame.indices) {
            val chan = chans[ch]
            var o = 0
            for (blk in 0 until FRAME / FULL_BLOCK) {
                val base = blk * FULL_BLOCK
                for (i in 0 until FULL_BLOCK) fFresh[i] = frame[ch][base + i]
                chan.processBlock(fFresh, fOut)
                for (i in 0 until 2 * FULL_BLOCK) out[ch][o++] = fOut[i]
            }
            // Phase 3l-B: Eq10 UNIQUE hinter der Dsx-Kette auf dem 2x-
            // Ausgang (96/88.2 kHz), 64er-Raster wie die Firmware
            // (Execute-Wrapper 128er-Calls = 2x64 Subchunks, KNOWLEDGE
            // 303). Abnahme: 3584/3584 Samples bit-identisch zur
            // Emulator-Referenz.
            val eq = eqs[ch]
            for (off in 0 until 2 * FRAME step Eq10Channel.BLOCK) {
                eq.processBlock64(out[ch], off)
            }
        }
        return out
    }

    /** Eq10-Koeffizienten der Ausgabeabtastrate (2x sr -> Mode 3 bei 48 kHz, Mode 2 bei 44.1 kHz). */
    private fun eq10Coeffs(): Array<FloatArray> =
        if (sampleRate == 44100) Eq10Tables.COEFFS_882 else Eq10Tables.COEFFS_96K

    /**
     * Schreibt alle Bloecke eines queueInput-Aufrufs in EINEN Output-Buffer
     * (Bugfix aus Phase 3d: frueher wurde pro Block replaceOutputBuffer()
     * aufgerufen, was einen noch nicht abgeholten Buffer kommentarlos
     * ueberschrieben hat). Jeder Block ist 512 Werte je Kanal @2x.
     */
    private fun emitFrames(outBlocks: List<Array<FloatArray>>) {
        if (outBlocks.isEmpty()) return
        // Mid-stream-Einschalten: State lazy nachziehen (onConfigure lief im
        // Bypass nicht durch den aktiven Zweig).
        if (enabled && antiClip && acLp.size < channels) initAntiClipState(2 * sampleRate, channels)
        val frameLen = outBlocks[0][0].size
        val total = outBlocks.sumOf { it[0].size }
        val buffer = replaceOutputBuffer(total * channels * (if (floatIn) 4 else 2))
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (block in outBlocks) {
            for (s in 0 until frameLen) {
                for (ch in 0 until channels) {
                    // Anti-Clip nur im aktiven ClearAudio-Modus: die Site-1-
                    // Upsampling-Pfade (enabled=false bzw. Bypass) brauchen
                    // keine Absenkung, dort bleibt der Pegel 1:1.
                    val v = if (enabled && antiClip) applyAntiClip(ch, block[ch][s]) else block[ch][s]
                    if (floatIn) {
                        buffer.putInt(v.toBits())
                    } else {
                        // Werte bleiben in [-1, 1); *32768 in f32 wie gehabt
                        val iv = (v * 32768f).toInt().coerceIn(-32768, 32767)
                        buffer.putShort(iv.toShort())
                    }
                }
            }
        }
        buffer.flip()
    }

    companion object {
        const val FRAME = 256
        const val FULL_BLOCK = 64

        /** Anti-Clip v2: -9 dB Vorabsenkung (10^(-9/20)), Split @9 kHz,
         *  adaptiver HF-Gain gegen den Env-Deckel T = -31 dBFS (10^(-31/20),
         *  Attack 10 ms / Release 150 ms), Knie ab 0.9, Asymptote exakt 1.0. */
        const val ANTI_CLIP_SCALE = 0.35481338f
        const val ANTI_CLIP_KNEE = 0.9f
        const val ANTI_CLIP_T = 0.028183829f
        const val ANTI_CURVE = 12.5f
    }
}
