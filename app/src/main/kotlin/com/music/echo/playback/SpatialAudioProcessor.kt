package com.music.echo.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

class SpatialAudioProcessor : BaseAudioProcessor() {
    var enabled = false
    var strength = 1.0f // 1.0 = normal, > 1.0 = wider

    private var currentEncoding = C.ENCODING_PCM_16BIT

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.channelCount != 2) {
            // Spatial only makes sense for stereo; pass through mono/surround untouched
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        currentEncoding = inputAudioFormat.encoding
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!enabled || strength == 1.0f) {
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

        for (i in 0 until sampleCount) {
            val left = inputBuffer.short
            val right = inputBuffer.short

            val mid = (left + right) / 2.0
            val side = (left - right) / 2.0

            val newSide = side * strength

            val newLeft = (mid + newSide).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val newRight = (mid - newSide).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

            outputBuffer.putShort(newLeft.toShort())
            outputBuffer.putShort(newRight.toShort())
        }

        outputBuffer.flip()
    }

    private fun processFloat(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        val sampleCount = remaining / 8 // 2 channels, 4 bytes per float
        val outputBuffer = replaceOutputBuffer(remaining)

        for (i in 0 until sampleCount) {
            val left = inputBuffer.float.toDouble()
            val right = inputBuffer.float.toDouble()

            val mid = (left + right) / 2.0
            val side = (left - right) / 2.0

            val newSide = side * strength

            val newLeft = (mid + newSide).coerceIn(-1.0, 1.0).toFloat()
            val newRight = (mid - newSide).coerceIn(-1.0, 1.0).toFloat()

            outputBuffer.putFloat(newLeft)
            outputBuffer.putFloat(newRight)
        }

        outputBuffer.flip()
    }
}

