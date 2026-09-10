package com.music.echo.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToInt

class CrossfeedAudioProcessor : BaseAudioProcessor() {
    var enabled = false
    var strength = 0.2f // How much opposite channel to mix in (0.0 to 1.0)

    private var currentEncoding = C.ENCODING_PCM_16BIT

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        currentEncoding = inputAudioFormat.encoding
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!enabled || strength <= 0.0f) {
            val remaining = inputBuffer.remaining()
            if (remaining == 0) return
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
            return
        }

        when (currentEncoding) {
            C.ENCODING_PCM_16BIT -> processInt16(inputBuffer)
            C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer)
            else -> {
                val remaining = inputBuffer.remaining()
                if (remaining == 0) return
                replaceOutputBuffer(remaining).put(inputBuffer).flip()
            }
        }
    }

    private fun processInt16(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        val sampleCount = remaining / 4 // 2 channels, 16 bit
        val outputBuffer = replaceOutputBuffer(remaining)

        val normalization = 1.0f / (1.0f + strength)

        for (i in 0 until sampleCount) {
            val left = inputBuffer.short.toFloat()
            val right = inputBuffer.short.toFloat()

            val newLeft = (left + right * strength) * normalization
            val newRight = (right + left * strength) * normalization

            outputBuffer.putShort(newLeft.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            outputBuffer.putShort(newRight.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }

        outputBuffer.flip()
    }

    private fun processFloat(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        val sampleCount = remaining / 8 // 2 channels, 4 bytes per float
        val outputBuffer = replaceOutputBuffer(remaining)

        val normalization = 1.0f / (1.0f + strength)

        for (i in 0 until sampleCount) {
            val left = inputBuffer.float
            val right = inputBuffer.float

            val newLeft = ((left + right * strength) * normalization).coerceIn(-1.0f, 1.0f)
            val newRight = ((right + left * strength) * normalization).coerceIn(-1.0f, 1.0f)

            outputBuffer.putFloat(newLeft)
            outputBuffer.putFloat(newRight)
        }

        outputBuffer.flip()
    }
}

