package com.motionmagic.ai.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.*

/** Streaming PCM16 gain, soft gate, fades and two-shelf tone control. No whole-track buffers. */
@UnstableApi
class StudioAudioProcessor(private val volume: Float, private val durationMs: Long,
    private val fadeInMs: Long, private val fadeOutMs: Long,
    private val bass: Float = 1f, private val treble: Float = 1f, private val gate: Boolean = false) : BaseAudioProcessor() {
    private var frames = 0L
    private var low = FloatArray(0)
    private var highLow = FloatArray(0)
    override fun onConfigure(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (format.encoding != C.ENCODING_PCM_16BIT || format.channelCount !in 1..8 || format.sampleRate <= 0)
            throw AudioProcessor.UnhandledAudioFormatException(format)
        return format
    }
    override fun onFlush() { frames = 0; low = FloatArray(inputAudioFormat.channelCount.coerceIn(1, 8)); highLow = FloatArray(low.size) }
    override fun queueInput(input: ByteBuffer) {
        val output = replaceOutputBuffer(input.remaining())
        val channels = inputAudioFormat.channelCount
        val rate = inputAudioFormat.sampleRate
        val a = (1 - exp(-2 * PI * 250 / rate)).toFloat()
        val b = (1 - exp(-2 * PI * 4000 / rate)).toFloat()
        while (input.remaining() >= channels * 2) {
            val time = frames * 1000.0 / rate
            val fade = min(if (fadeInMs > 0) (time / fadeInMs).coerceIn(0.0, 1.0) else 1.0,
                if (fadeOutMs > 0) ((durationMs - time) / fadeOutMs).coerceIn(0.0, 1.0) else 1.0).toFloat()
            for (channel in 0 until channels) {
                val x = input.short / 32768f
                low[channel] += a * (x - low[channel]); highLow[channel] += b * (x - highLow[channel])
                val tone = x + (bass - 1f) * low[channel] + (treble - 1f) * (x - highLow[channel])
                val gateGain = if (gate) ((abs(x) - .004f) / .012f).coerceIn(0f, 1f) else 1f
                output.putShort((tone * volume * fade * gateGain * 32767).roundToInt().coerceIn(-32768, 32767).toShort())
            }
            frames++
        }
        output.flip()
    }
}
