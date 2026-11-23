package com.mardous.booming.playback.processor

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.tarvos.clearaudio.DsxConfig
import com.tarvos.clearaudio.DsxSimulator
import com.tarvos.clearaudio.VptChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Status des ClearAudio+-Processors fuer die UI.
 *
 * [enabled] = Master-Schalter-Stellung (Nutzer-Setting),
 * [activeInChain] = der Processor verarbeitet tatsächlich im Moment (Format
 * unterstützt: 44.1/48 kHz, 1-2 Kanäle, PCM16/Float). [sampleRate]/
 * [channelCount] beschreiben das EINGANGS-Format, das der Processor zuletzt
 * gesehen hat (0 = noch kein Stream). Die Ausgabe läuft mit der doppelten
 * Rate (88.2/96 kHz), siehe [ClearAudioAudioProcessor].
 */
data class ClearAudioStatus(
    val enabled: Boolean,
    val activeInChain: Boolean,
    val sampleRate: Int,
    val channelCount: Int
)

/**
 * ClearAudio+ (DSEE AI) Nachbau: fuegt den 1:1-portierten Sony-DSP-Stack
 * (DSEE-AI-NN + Exciter + Upcon, reverse-engineered aus dem Walkman-Audio-
 * Treiber NW-A105/A100, keine Sony-Binaries) als AudioProcessor in die
 * DefaultAudioSink-Kette ein.
 *
 * Aktiv bei 44.1 kHz und 48 kHz, 1-2 Kanaele (16-bit oder Float); alles
 * andere wird unangetastet durchgereicht (NOT_SET-Bypass, kein Werfen einer
 * UnhandledAudioFormatException, da die DefaultAudioSink daraus eine
 * ConfigurationException macht, die die Wiedergabe komplett stoppt).
 *
 * Phase 3e - decode-faithful 2x-Architektur (KNOWLEDGE 196/198, "MODE =
 * Upsampling-Faktor"): dsx_process liefert je 64er-Eingabeblock MODE*64 =
 * 128 Ausgangswerte bei doppelter Rate. Der Processor konfiguriert deshalb
 * 44.1 -> 88.2 kHz bzw. 48 -> 96 kHz Ausgabe:
 *
 *   out2x = site1_upcon(raw) + wet2x
 *
 *  - site1_upcon(raw) = Fullband-2x-Upsampling des Eingangs mit der
 *    Firmware-Fenstertabelle (0xc2ddc @48k / 0xc2d78 @44.1k, gemessen:
 *    Gain 1.0, Image-Rejection ~47 dB).
 *  - wet2x = GUCF-Band (downcon(IIR(raw)) -> adaptiver GUCF-Kern -> upcon
 *    x8) plus Rausch-Upcon, beide akkumuliert der Firmware nach w4=1
 *    direkt auf den Site-1-Output in bw+0x714 (kein separater Mischpunkt).
 *
 * KEINE Normalisierung, KEIN adaptiver Gain: gemessen liegt das Wet-Band
 * natuerlich ca. -34 dB unter dem Dry (ueber den Pegelbereich stabil) und
 * wirkt als ~+1 dB HF-Anhebung im 18-24-kHz-Band. Die Phase-3d-RMS-
 * Normalisierung (TARGET_WET_RATIO) hob genau dieses Signal um 40-80 dB
 * an und war die Ursache des hoerbaren Hochton-Rauschens ("shh shh").
 *
 * Ist der Schalter beim Configure aus, gibt der Processor NOT_SET zurueck
 * (1x-Passthrough, keine Kosten); ein mid-stream Einschalten greift dann
 * erst beim naechsten Stream. Wird der Schalter mitten im Stream abge-
 * schaltet, bleibt das Ausgabeformat 2x (Formatwechsel nur per
 * onConfigure): dann laeuft das reine Site-1-Upsampling ohne Effektanteil.
 *
 * Verarbeitet in 256er-Bloecken mit Zero-Pad-Flush am Streamende; alle
 * Bloecke eines queueInput-Aufrufs werden in EINEN Output-Buffer geschrieben
 * (BaseAudioProcessor.replaceOutputBuffer ueberschreibt noch nicht
 * abgeholte Buffer kommentarlos - Bugfix aus Phase 3d).
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

    /**
     * VPT-Studio-Stufe (KNOWLEDGE 368-375, "Studio-Kette"): 1:1-Port des gegen
     * den Live-Firmware-Emulator validierten Modells. Mono-Summe (L+R)/2 ->
     * dekorreliertes Stereo via rDFT 1024, GAIN-Kurven R1 -> links / R2 ->
     * rechts, hermetische iDFT, MAC-Ring. Nur bei 48-kHz-Eingang; die Kurven
     * sind fuer 48 kHz extrahiert (bei 44.1 kHz bleibt die Stufe stumm
     * ueberbrueckt). Ersetzt das Signal, das in die DsxSim-Kette geht, wie in
     * der Firmware die Studio-Stufe vor dem Upcon sitzt.
     *
     * Der finale outl/outr-Mischfaktor der Firmware (DPFvpt_proc Epilog) ist
     * noch nicht bit-exakt geklaert; erste 256 Frames liefert die Stille-Boot-
     * Phase des Rings.
     */
    private var vpt: VptChannel? = null
    private var vptEnabled = false
    private val vptInBuf = FloatArray(FRAME)
    private val vptOutL = FloatArray(FRAME)
    private val vptOutR = FloatArray(FRAME)

    private var simulator: DsxSimulator? = null
    private var simConfig: Config? = null
    private var pending: Array<DoubleArray>? = null   // je Kanal bis 255 Samples
    private var pendingCount = 0

    /** Master-Schalter (Settings/UI). Bei false mid-stream: Site-1-Upsampling. */
    @Synchronized
    fun setEnabled(value: Boolean) {
        enabled = value
        _status.value = _status.value.copy(enabled = value)
    }

    /** VPT-Studio-Stufe zu-/abschalten (UI). Greift ab dem naechsten 256er-Block. */
    @Synchronized
    fun setVptEnabled(value: Boolean) {
        vptEnabled = value
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val sr = inputAudioFormat.sampleRate
        val ok = (sr == 44100 || sr == 48000) &&
            (inputAudioFormat.channelCount == 1 || inputAudioFormat.channelCount == 2) &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        if (!ok || !enabled) {
            // Bypass: NOT_SET signalisiert der Pipeline, diesen Processor zu
            // ueberbruecken (Format unguenstig ODER Schalter aus: dann kostet
            // ClearAudio+ gar nichts und die Ausgabe bleibt 1x). NIEMALS
            // UnhandledAudioFormatException werfen.
            sampleRate = if (ok) sr else 0
            channels = if (ok) inputAudioFormat.channelCount else 0
            floatIn = false
            if (!ok) {
                simulator = null
                vpt = null
                simConfig = null
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
        // Decode-faithful: MODE=2 bedeutet 2x-Upsampling, dsx_process gibt
        // 128 Werte je 64er-Block aus. Ausgabeformat = doppelte Rate,
        // Kanalzahl/Encoding unveraendert.
        return AudioProcessor.AudioFormat(
            2 * sr, channels, inputAudioFormat.encoding
        )
    }

    override fun onFlush() {
        // angebrochene Frames verwerfen, Simulator-Zustand aber NICHT anfassen:
        // der Effekt soll ueber Track-Grenzen hinweg weiterlaufen wie im Original.
        pending = null
        pendingCount = 0
    }

    override fun onReset() {
        simulator = null
        vpt = null
        simConfig = null
        pending = null
        pendingCount = 0
    }

    override fun onQueueEndOfStream() {
        val sim = simulator
        val pend = pending
        if (sim != null && pend != null && pendingCount > 0) {
            // angebrochenen Frame mit Nullen auffuellen und verarbeiten (Firmware-Flush)
            val frame = Array(channels) { ch ->
                DoubleArray(FRAME) { if (it < pendingCount) pend[ch][it] else 0.0 }
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
        // mit der der Buffer erzeugt wurde (sonst wird jedes Sample-Paar
        // byte-gedreht: aus 0.026 wird 0.68).
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        val cfg = Config(sampleRate, channels, floatIn)
        var sim = simulator
        if (sim == null || simConfig != cfg) {
            // Stream-Format hat sich geaendert (z. B. 44.1 -> 48 kHz):
            // Simulator mit neuer Rate neu aufbauen.
            sim = DsxSimulator(DsxConfig(ch = channels, sr = sampleRate)).also {
                simulator = it
                simConfig = cfg
                pending = null
                pendingCount = 0
            }
            // VPT-Studio-Kanal gehoert zur 48-kHz-Konfiguration wie der
            // Simulator; bei jedem Rebuild frisch starten (Boot-Stille).
            vpt = if (sampleRate == 48000 && channels == 2) VptChannel() else null
        }

        val outBlocks = ArrayList<Array<DoubleArray>>()
        val bytesPerFrame = channels * (if (floatIn) 4 else 2)
        while (inputBuffer.remaining() >= bytesPerFrame) {
            val pend = pending ?: Array(channels) { DoubleArray(FRAME) }.also { pending = it }
            for (ch in 0 until channels) {
                pend[ch][pendingCount] =
                    if (floatIn) Float.fromBits(inputBuffer.int).toDouble()
                    else inputBuffer.short / 32768.0
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
    private fun processFrame(sim: DsxSimulator, frame: Array<DoubleArray>): Array<DoubleArray> {
        return if (enabled) {
            val vptFrame = applyVpt(frame)
            val (dry, wet) = sim.executeFrameDryWet(vptFrame)
            Array(vptFrame.size) { ch ->
                val d = dry[ch]
                val w = wet[ch]
                DoubleArray(d.size) { i -> d[i] + w[i] }
            }
        } else {
            sim.executeFrameUpsample2x(frame)
        }
    }

    /**
     * VPT-Studio-Stufe (nur 48 kHz, 2 Kanaele): bildet die Mono-Summe auf
     * dekorreliertes Stereo ab und ersetzt damit das Eingangssignal der
     * DsxSim-Kette. Bei allen anderen Konfigurationen (44.1 kHz, Mono,
     * Schalter aus) bleibt das Frame unangetastet. VptChannel verarbeitet
     * puffernd: pro 256er-Block fallen 256 Frames an (Block 0 = Boot-Stille).
     */
    private fun applyVpt(frame: Array<DoubleArray>): Array<DoubleArray> {
        val ch = vpt ?: return frame
        if (!vptEnabled || frame.size < 2) return frame
        val l = frame[0]
        val r = frame[1]
        val n = l.size
        for (i in 0 until n) vptInBuf[i] = ((l[i] + r[i]) * 0.5).toFloat()
        val written = ch.process(vptInBuf, 0, vptInBuf, n, vptOutL, 0, vptOutR)
        val out = Array(2) { c -> DoubleArray(n) }
        for (i in 0 until n) {
            out[0][i] = if (i < written) vptOutL[i].toDouble() else 0.0
            out[1][i] = if (i < written) vptOutR[i].toDouble() else 0.0
        }
        return out
    }

    /**
     * Schreibt alle Bloecke eines queueInput-Aufrufs in EINEN Output-Buffer
     * (Bugfix aus Phase 3d: frueher wurde pro 256er-Block
     * replaceOutputBuffer() aufgerufen, was einen noch nicht abgeholten
     * Buffer kommentarlos ueberschrieben hat; bei Sink-Chunks > 256 Frames
     * gingen alle Bloecke bis auf den letzten verloren, daher "spielt an,
     * aber nichts aendert sich"). Jeder Block ist bereits @2x (512 je
     * 256er-Frame je Kanal).
     */
    private fun emitFrames(outBlocks: List<Array<DoubleArray>>) {
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
                        buffer.putInt(v.toFloat().toBits())
                    } else {
                        val iv = (v * 32768.0).toInt().coerceIn(-32768, 32767)
                        buffer.putShort(iv.toShort())
                    }
                }
            }
        }
        buffer.flip()
    }

    companion object {
        const val FRAME = 256
    }
}
