package com.mardous.booming.playback.processor

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.tarvos.clearaudio.DsxConfig
import com.tarvos.clearaudio.DsxSimulator
import com.tarvos.clearaudio.FullChainChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Status des ClearAudio+-Processors fuer die UI.
 *
 * [enabled] = Master-Schalter-Stellung (Nutzer-Setting),
 * [activeInChain] = der Processor verarbeitet tatsächlich im Moment (Format
 * unterstützt: 48 kHz, 1-2 Kanäle, PCM16/Float). [sampleRate]/
 * [channelCount] beschreiben das EINGANGS-Format, das der Processor zuletzt
 * gesehen hat (0 = noch kein Stream). Die Ausgabe läuft mit der doppelten
 * Rate (96 kHz), siehe [ClearAudioAudioProcessor].
 */
data class ClearAudioStatus(
    val enabled: Boolean,
    val activeInChain: Boolean,
    val sampleRate: Int,
    val channelCount: Int
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
 * Aktiv bei 48 kHz, 1-2 Kanäle, PCM16/Float (die Chain-Koeffizienten
 * K65/Taps/RODATA sind die gemessene 48-kHz-Konfiguration; 44.1 kHz
 * benoetigt erst eine eigene Emulator-Referenz). Alles andere wird
 * unangetastet durchgereicht (NOT_SET-Bypass, kein Werfen einer
 * UnhandledAudioFormatException, da die DefaultAudioSink daraus eine
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

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val sr = inputAudioFormat.sampleRate
        // Phase 3f: NUR 48 kHz (die Chain-Koeffizienten sind die 48-kHz-
        // RODATA-Konfiguration; 44.1 kHz folgt erst mit eigener Referenz).
        val ok = sr == 48000 &&
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
                pending = null
                pendingCount = 0
            }
            _status.value = _status.value.copy(
                activeInChain = false,
                sampleRate = if (ok) sr else 0,
                channelCount = if (ok) inputAudioFormat.channelCount else 0
            )
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = sr
        channels = inputAudioFormat.channelCount
        floatIn = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        _status.value = _status.value.copy(activeInChain = true, sampleRate = sr, channelCount = channels)
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
        pending = null
        pendingCount = 0
    }

    override fun onQueueEndOfStream() {
        val pend = pending
        if (pend != null && pendingCount > 0) {
            // angebrochenen Frame mit Nullen auffuellen und verarbeiten (Firmware-Flush)
            val frame = Array(channels) { ch ->
                FloatArray(FRAME) { if (it < pendingCount) pend[ch][it] else 0f }
            }
            emitFrames(listOf(processFrame(simulator, frame)))
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
                fullCh = Array(channels) { FullChainChannel() }
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
     * Ein 256er-Eingabeframe -> 512 Ausgangswerte je Kanal @2x Rate.
     * Einschalterstellung entscheidet ueber den Modus; das Ausgabeformat
     * bleibt in beiden Faellen 2x (siehe KDoc der Klasse).
     */
    private fun processFrame(sim: DsxSimulator, frame: Array<FloatArray>): Array<FloatArray> {
        return if (enabled) {
            processFrameFull(frame)
        } else {
            // Mid-stream aus: bewaehrtes Site-1-Upsampling der Phase 3e
            // (Firmware-Fenstertabelle, Image-Rejection ~47 dB).
            val dbl = Array(frame.size) { ch -> DoubleArray(frame[ch].size) { frame[ch][it].toDouble() } }
            sim.executeFrameUpsample2x(dbl).map { arr ->
                FloatArray(arr.size) { arr[it].toFloat() }
            }.toTypedArray()
        }
    }

    /**
     * Full-Chain-Pfad: je Kanal 4x 64er-Bloecke durch die Kette, Ausgabe
     * 4x128 = 512 Werte @2x. Der Kanal-State (hist/win/or notch u,v/D/
     * FIN-Rotation) sitzt in FullChainChannel und traegt ueber Frames fort.
     */
    private fun processFrameFull(frame: Array<FloatArray>): Array<FloatArray> {
        val chans = fullCh ?: Array(frame.size) { FullChainChannel() }
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
        }
        return out
    }

    /**
     * Schreibt alle Bloecke eines queueInput-Aufrufs in EINEN Output-Buffer
     * (Bugfix aus Phase 3d: frueher wurde pro Block replaceOutputBuffer()
     * aufgerufen, was einen noch nicht abgeholten Buffer kommentarlos
     * ueberschrieben hat). Jeder Block ist 512 Werte je Kanal @2x.
     */
    private fun emitFrames(outBlocks: List<Array<FloatArray>>) {
        if (outBlocks.isEmpty()) return
        val frameLen = outBlocks[0][0].size
        val total = outBlocks.sumOf { it[0].size }
        val buffer = replaceOutputBuffer(total * channels * (if (floatIn) 4 else 2))
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (block in outBlocks) {
            for (s in 0 until frameLen) {
                for (ch in 0 until channels) {
                    val v = block[ch][s]
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
    }
}
