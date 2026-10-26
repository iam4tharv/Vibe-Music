package com.music.vibe.playback.audio.aaudio

import android.content.Context
import com.music.vibe.playback.audio.AudioNativeBridge

/**
 * Kotlin facade over the unified native C++ AAudio Engine (`vibe_aaudio` via [AudioNativeBridge]).
 *
 * Ensures a single coordinated native engine instance without duplicate JNI symbol tables or
 * competing exclusive AAudio streams.
 */
object AAudioDirectEngine {

    val isInitialized: Boolean
        get() = AudioNativeBridge.isInitialized

    val isExclusive: Boolean
        get() = AudioNativeBridge.isExclusive()

    val activeSampleRate: Int
        get() = AudioNativeBridge.getSampleRate()

    val activeFramesPerBurst: Int
        get() = AudioNativeBridge.getFramesPerBurst()

    val hardwareNativeSampleRate: Int
        get() = AudioNativeBridge.hardwareSampleRate

    val hardwareFramesPerBurst: Int
        get() = AudioNativeBridge.hardwareFramesPerBurst

    /**
     * Queries hardware optimal sample rate and buffer burst size from [android.media.AudioManager]
     * to prevent Android AudioFlinger software resampling.
     */
    fun queryHardwareParams(context: Context) {
        AudioNativeBridge.queryHardwareParams(context)
    }

    /**
     * Opens an exclusive, low-latency AAudio stream matching the desired or hardware sample rate.
     */
    @Synchronized
    fun open(sampleRate: Int = hardwareNativeSampleRate, channelCount: Int = 2): Boolean {
        return AudioNativeBridge.initStream(
            sampleRate = sampleRate,
            channelCount = channelCount,
            framesPerBurst = hardwareFramesPerBurst,
        )
    }

    @Synchronized
    fun start(): Boolean = AudioNativeBridge.start()

    @Synchronized
    fun pause(): Boolean = AudioNativeBridge.pause()

    @Synchronized
    fun flush(): Boolean = AudioNativeBridge.flush()

    @Synchronized
    fun stop(): Boolean = AudioNativeBridge.stop()

    @Synchronized
    fun close() {
        AudioNativeBridge.close()
    }

    fun write(floatData: FloatArray, offset: Int, numFrames: Int, timeoutNs: Long = 100_000_000L): Int {
        return AudioNativeBridge.write(floatData, offset, numFrames, timeoutNs)
    }

    fun getXRunCount(): Int = AudioNativeBridge.getXRunCount()
}
