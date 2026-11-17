package com.mardous.booming.playback.processor

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.tarvos.clearaudio.DsxConfig
import com.tarvos.clearaudio.DsxSimulator
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
 * [channelCount] beschreiben das Format, das der Processor zuletzt gesehen
 * hat (0 = noch kein Stream).
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
 * Verarbeitet in 256er-Bloecken mit Zero-Pad-Flush am Streamende.
 *
 * Wichtig zur DSP-Verkabelung (ehrlich dokumentiert): der portierte
 * dsx_process-Pfad (DsxSimulator) liefert der Firmware-Analyse nach nur das
 * rekonstruierte Hochtonband zurueck, kein Vollbandsignal. Messungen am
 * Referenz-Port: fir2band ist ein ~12-18-kHz-Bandpass (1-kHz-Sinus liegt am
 * Ausgang bei ca. -77 dB), und das reine Wet-Signal liegt je nach Material
 * bei -55 bis -97 dB (Referenz-treu, die Julia-Fixtures zeigen dasselbe).
 * Die Summenstelle und die Aussteuerung des Wet-Bands ("replace"-Semantik im
 * high_pow-Register, fehlende h+0x130-IIR-Koeffizienten) sind im Firmware-
 * Disassembly nicht dekodiert. Dieser Processor macht deshalb die klassische
 * Bandwidth-Extension-Mischung mit adaptiver Aussteuerung:
 *
 *   out = dry + g * wet,   g pro 256er-Frame so, dass
 *   rms(g*wet) ~ TARGET_WET_RATIO * rms(dry)
 *
 * Der Dry-Anteil wird um die gemessene Gruppenlaufzeit des Wet-Pfads (exakt
 * 64 Samples bei 44.1 und 48 kHz, Impulsmessung) verzoegert, damit sich die
 * Hochtonanteile phasenrichtig addieren. Die adaptives Gain-Design garantiert:
 * effektiver, hoerbarer HF-Anteil bei lautheitsneutralem Gesamtsignal, auch
 * wenn das rohe Wet-Signal je nach Material um 40 dB variiert. Attack max
 * +18 dB/Frame, Release ca. -3 dB/Frame, Silence-Floor (kein Boost des
 * Rauschens in Stillen), Absolute Kappung bei MAX_WET_GAIN.
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

    private var simulator: DsxSimulator? = null
    private var simConfig: Config? = null
    private var pending: Array<DoubleArray>? = null   // je Kanal bis 255 Samples
    private var pendingCount = 0
    private var dryTail: Array<DoubleArray>? = null   // je Kanal letzte DRY_DELAY Dry-Samples
    private var wetGain = 0.0                          // adaptiver Wet-Gain (Attack/Release)

    /** Master-Schalter (Settings/UI). Bei false: byteweises Passthrough. */
    @Synchronized
    fun setEnabled(value: Boolean) {
        enabled = value
        _status.value = _status.value.copy(enabled = value)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val sr = inputAudioFormat.sampleRate
        val ok = (sr == 44100 || sr == 48000) &&
            (inputAudioFormat.channelCount == 1 || inputAudioFormat.channelCount == 2) &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        if (!ok) {
            // Bypass: NOT_SET signalisiert der Pipeline, diesen Processor zu
            // ueberbruecken. NIEMALS UnhandledAudioFormatException werfen.
            sampleRate = 0
            channels = 0
            floatIn = false
            _status.value = _status.value.copy(activeInChain = false, sampleRate = 0, channelCount = 0)
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = sr
        channels = inputAudioFormat.channelCount
        floatIn = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        _status.value = _status.value.copy(activeInChain = true, sampleRate = sr, channelCount = channels)
        return inputAudioFormat
    }

    override fun onFlush() {
        // angebrochene Frames verwerfen, Simulator-Zustand aber NICHT anfassen:
        // der Effekt soll ueber Track-Grenzen hinweg weiterlaufen wie im Original.
        pending = null
        pendingCount = 0
    }

    override fun onReset() {
        simulator = null
        simConfig = null
        pending = null
        pendingCount = 0
        dryTail = null
        wetGain = 0.0
    }

    override fun onQueueEndOfStream() {
        val sim = simulator
        val pend = pending
        if (enabled && sim != null && pend != null && pendingCount > 0) {
            // angebrochenen Frame mit Nullen auffuellen und verarbeiten (Firmware-Flush)
            val frame = Array(channels) { ch ->
                DoubleArray(FRAME) { if (it < pendingCount) pend[ch][it] else 0.0 }
            }
            val wet = scaleWet(sim.executeFrame(frame), frame)
            emitFrames(listOf(wet), listOf(frame))
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

        if (!enabled) {
            // 1:1-Passthrough wenn per Settings abgeschaltet
            val buffer = replaceOutputBuffer(remaining)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(inputBuffer)
            buffer.flip()
            return
        }

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
                dryTail = null
            }
        }

        val outBlocks = ArrayList<Array<DoubleArray>>()
        val dryBlocks = ArrayList<Array<DoubleArray>>()
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
                // Dry-Kopie vor der Verarbeitung: das Original wird als
                // verzögertes Basisband wieder zugemischt.
                val dry = Array(channels) { ch -> pend[ch].copyOf() }
                val wet = scaleWet(sim.executeFrame(pend), dry)
                outBlocks.add(wet)
                dryBlocks.add(dry)
                pendingCount = 0
            }
        }
        if (outBlocks.isNotEmpty()) {
            emitFrames(outBlocks, dryBlocks)
        }
    }

    /**
     * Adaptiver Wet-Gain: skaliert das rohe Wet-Signal so, dass sein RMS je
     * Frame TARGET_WET_RATIO des Dry-RMS erreicht (das rohe Wet liegt je
     * nach Material bei -55 bis -97 dB und waere bei festem Gain unhoerbar
     * bzw. bei festem Hoch-Gain bei bandlimitiertem Material uebersteuert).
     * Attack max +18 dB/Frame, Release ca. -3 dB/Frame, Silence-Floor,
     * Kappung bei MAX_WET_GAIN. Der Gain gilt global (beide Kanaele), um
     * das Stereo-Bild nicht zu verwackeln.
     */
    private fun scaleWet(wet: Array<DoubleArray>, dry: Array<DoubleArray>): Array<DoubleArray> {
        var dryAcc = 0.0
        var wetAcc = 0.0
        for (ch in 0 until dry.size) {
            for (i in 0 until dry[ch].size) {
                dryAcc += dry[ch][i] * dry[ch][i]
                wetAcc += wet[ch][i] * wet[ch][i]
            }
        }
        val n = dry.size * dry[0].size
        val dryRms = Math.sqrt(dryAcc / n)
        val wetRms = Math.sqrt(wetAcc / n)
        if (dryRms < MIN_DRY_RMS) {
            // Stille: Wet nicht hochziehen (Rauschfloor-Boost vermeiden)
            wetGain *= 0.7
        } else {
            val gTarget = (TARGET_WET_RATIO * dryRms / (wetRms + 1e-12))
                .coerceAtMost(MAX_WET_GAIN)
            val attackCap = maxOf(wetGain * 8.0, 1.0)
            wetGain = if (gTarget > wetGain) minOf(gTarget, attackCap)
                      else maxOf(gTarget, wetGain * 0.7)
        }
        val g = wetGain
        return Array(wet.size) { ch ->
            DoubleArray(wet[ch].size) { i -> wet[ch][i] * g }
        }
    }

    /**
     * Schreibt alle Bloecke eines queueInput-Aufrufs in EINEN Output-Buffer
     * (Bugfix: frueher wurde pro 256er-Block replaceOutputBuffer() aufgerufen,
     * was einen noch nicht abgehholten Buffer kommentarlos ueberschrieben hat;
     * bei Sink-Chunks > 256 Frames gingen alle Bloecke bis auf den letzten
     * verloren, daher "spielt an, aber nichts aendert sich").
     *
     * Mischung: out[t] = dry[t - DRY_DELAY] + wet[t] (wet bereits mit dem
     * adaptiven Gain von scaleWet skaliert). Die ersten DRY_DELAY Samples
     * eines Aufrufs kommen aus dryTail (letzte 64 Dry-Samples des
     * Vorgaenger-Aufrufs; am Streamanfang Stille).
     */
    private fun emitFrames(outBlocks: List<Array<DoubleArray>>, dryBlocks: List<Array<DoubleArray>>) {
        if (outBlocks.isEmpty()) return
        val tail = dryTail ?: Array(channels) { DoubleArray(DRY_DELAY) }.also { dryTail = it }
        val frameLen = dryBlocks[0][0].size
        val total = dryBlocks.sumOf { it[0].size }
        val buffer = replaceOutputBuffer(total * channels * (if (floatIn) 4 else 2))
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        var t = 0
        for (bi in dryBlocks.indices) {
            val dry = dryBlocks[bi]
            val wet = outBlocks[bi]
            for (s in 0 until frameLen) {
                val j = t - DRY_DELAY
                for (ch in 0 until channels) {
                    val dryVal = if (j < 0) tail[ch][j + DRY_DELAY]
                                 else dryBlocks[j / frameLen][ch][j % frameLen]
                    val v = dryVal + wet[ch][s]
                    if (floatIn) {
                        buffer.putInt(v.toFloat().toBits())
                    } else {
                        val iv = (v * 32768.0).toInt().coerceIn(-32768, 32767)
                        buffer.putShort(iv.toShort())
                    }
                }
                t++
            }
        }
        buffer.flip()
        // dryTail auf die letzten DRY_DELAY Dry-Samples dieses Aufrufs setzen
        for (ch in 0 until channels) {
            var k = 0
            for (i in total - DRY_DELAY until total) {
                tail[ch][k++] = dryBlocks[i / frameLen][ch][i % frameLen]
            }
        }
    }

    companion object {
        const val FRAME = 256

        /** Gruppenlaufzeit des Wet-Pfads in Samples (Impulsmessung, bei 44.1 und 48 kHz identisch). */
        const val DRY_DELAY = 64

        /**
         * Ziel-Anteil des Wet-Bands am Dry-Signal (RMS je Frame).
         * 0.3 = Wet ca. -10.5 dB unter Dry: gemessen +2.3 bis +3 dB HF-Shelf
         * bei bereits hoher Frequenz (48/44.1 kHz), +15 dB Rekonstruktion bei
         * bandlimitiertem Material, Gesamt-Lautheit +-0.4 dB.
         * Hoer-Tuning-Wert.
         */
        const val TARGET_WET_RATIO = 0.3

        /** Dry-RMS-Schwelle darunter kein Wet-Boost erfolgt (Stille/Floor). */
        const val MIN_DRY_RMS = 1e-3

        /** Absolute Kappung des adaptiven Gains (Schutz vor Rausch-Explosion). */
        const val MAX_WET_GAIN = 1e4
    }
}
