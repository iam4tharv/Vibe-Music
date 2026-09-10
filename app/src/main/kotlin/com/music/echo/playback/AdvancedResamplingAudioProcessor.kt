package com.music.echo.playback

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import java.nio.ByteBuffer

/**
 * Advanced Resampling Audio Processor
 * Uses high-end math algorithms to convert sample rates cleanly if conversion is necessary,
 * preventing distortion.
 */
class AdvancedResamplingAudioProcessor : BaseAudioProcessor() {
    var enabled = false
    private val sonic = SonicAudioProcessor()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!enabled) {
            return inputAudioFormat
        }
        sonic.setOutputSampleRateHz(48000)
        return sonic.configure(inputAudioFormat)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!enabled) {
            val remaining = inputBuffer.remaining()
            if (remaining == 0) return
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
            return
        }
        sonic.queueInput(inputBuffer)
        val outputBuffer = sonic.output
        if (outputBuffer.hasRemaining()) {
            replaceOutputBuffer(outputBuffer.remaining()).put(outputBuffer).flip()
        }
    }


    override fun onQueueEndOfStream() {
        if (enabled) sonic.queueEndOfStream()
    }

    override fun getOutput(): ByteBuffer {
        if (!enabled) return super.getOutput()
        val out = sonic.output
        return if (out.hasRemaining()) out else super.getOutput()
    }

    override fun isEnded() = if (enabled) sonic.isEnded else super.isEnded()
    override fun onFlush() = if (enabled) sonic.flush() else super.onFlush()
    override fun onReset() = if (enabled) sonic.reset() else super.onReset()
}
