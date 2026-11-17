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

/**
 * ClearAudio+ (DSEE AI) Nachbau: fuegt den 1:1-portierten Sony-DSP-Stack
 * (DSEE-AI-NN + Exciter + Upcon, reverse-engineered aus dem Walkman-Audio-
 * Treiber NW-A105/A100, keine Sony-Binaries) als AudioProcessor in die
 * DefaultAudioSink-Kette ein.
 *
 * Nur aktiv bei 48 kHz und 1-2 Kanaelen (16-bit oder Float); alles andere
 * wird unangetastet durchgereicht. Verarbeitet in 256er-Bloecken mit
 * Zero-Pad-Flush am Streamende.
 */
@OptIn(UnstableApi::class)
class ClearAudioAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var enabled = false

    private var simulator: DsxSimulator? = null
    private var channels = 0
    private var floatIn = false
    private var pending: Array<DoubleArray>? = null   // je Kanal bis 255 Samples
    private var pendingCount = 0

    @Synchronized
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val ok = inputAudioFormat.sampleRate == 48000 &&
            (inputAudioFormat.channelCount == 1 || inputAudioFormat.channelCount == 2) &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        if (!ok) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        floatIn = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
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
        pending = null
        pendingCount = 0
    }

    override fun onQueueEndOfStream() {
        val sim = simulator
        val pend = pending
        if (enabled && sim != null && pend != null && pendingCount > 0) {
            // angebrochenen Frame mit Nullen auffuellen und verarbeiten (Firmware-Flush)
            val frame = Array(channels) { ch ->
                DoubleArray(FRAME) { if (it < pendingCount) pend[ch][it] else 0.0 }
            }
            emitFrame(sim, frame)
        }
        pending = null
        pendingCount = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (!enabled) {
            // 1:1-Passthrough wenn per Settings abgeschaltet
            val buffer = replaceOutputBuffer(remaining)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(inputBuffer)
            buffer.flip()
            return
        }

        val sim = simulator ?: DsxSimulator(DsxConfig(ch = channels, sr = 48000)).also { simulator = it }
        val pend = pending ?: Array(channels) { DoubleArray(FRAME) }.also { pending = it }

        val bytesPerFrame = channels * (if (floatIn) 4 else 2)
        while (inputBuffer.remaining() >= bytesPerFrame) {
            for (ch in 0 until channels) {
                pend[ch][pendingCount] =
                    if (floatIn) Float.fromBits(inputBuffer.int).toDouble()
                    else inputBuffer.short / 32768.0
            }
            pendingCount++
            if (pendingCount == FRAME) {
                emitFrame(sim, pend)
                pendingCount = 0
            }
        }
    }

    private fun emitFrame(sim: DsxSimulator, frame: Array<DoubleArray>) {
        val out = sim.executeFrame(frame)
        val n = out[0].size
        val size = if (floatIn) n * channels * 4 else n * channels * 2
        val buffer = replaceOutputBuffer(size)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (s in 0 until n) {
            for (ch in 0 until channels) {
                if (floatIn) {
                    buffer.putInt(out[ch][s].toFloat().toBits())
                } else {
                    val v = (out[ch][s] * 32768.0).toInt().coerceIn(-32768, 32767)
                    buffer.putShort(v.toShort())
                }
            }
        }
        buffer.flip()
    }

    companion object {
        const val FRAME = 256
    }
}
