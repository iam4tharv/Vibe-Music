package com.music.vibe.playback.audio

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Kotlin JNI Bridge to the native C++ AAudio implementation (`native-audio.cpp` / `vibe_aaudio`).
 *
 * Interfaces with low-latency and exclusive-mode AAudio hardware playback endpoints,
 * querying hardware native sample rate and frames-per-buffer via [AudioManager] to bypass
 * AudioFlinger system mixing.
 */
object AudioNativeBridge {

    private const val TAG = "AudioNativeBridge"

    var isLoaded: Boolean = false
        private set

    var isInitialized: Boolean = false
        private set

    var hardwareSampleRate: Int = 48000
        private set

    var hardwareFramesPerBurst: Int = 256
        private set

    init {
        try {
            System.loadLibrary("vibe_aaudio")
            isLoaded = true
            Log.i(TAG, "vibe_aaudio native library loaded successfully")
        } catch (t: Throwable) {
            Log.w(TAG, "vibe_aaudio native library could not be loaded: ${t.message}")
            isLoaded = false
        }
    }

    /**
     * Queries hardware optimal sample rate and buffer burst size from [AudioManager]
     * to prevent OS resampling.
     */
    fun queryHardwareParams(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val rateStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val burstStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)

        hardwareSampleRate = rateStr?.toIntOrNull() ?: 48000
        hardwareFramesPerBurst = burstStr?.toIntOrNull() ?: 256

        Log.i(TAG, "Queried AudioManager: hardwareSampleRate=$hardwareSampleRate Hz, burst=$hardwareFramesPerBurst")
    }

    /**
     * Initializes the native AAudio stream with hardware-matched sample rate and burst buffer capacity.
     */
    @Synchronized
    fun initStream(sampleRate: Int = hardwareSampleRate, channelCount: Int = 2, framesPerBurst: Int = hardwareFramesPerBurst): Boolean {
        if (!isLoaded) return false
        val targetRate = if (sampleRate > 0) sampleRate else hardwareSampleRate
        val targetBurst = if (framesPerBurst > 0) framesPerBurst else hardwareFramesPerBurst

        val success = nativeInit(targetRate, channelCount, targetBurst)
        isInitialized = success
        if (success) {
            val mode = if (isExclusive()) "EXCLUSIVE (MMAP Bypass)" else "SHARED"
            Log.i(TAG, "AAudio stream initialized at ${getSampleRate()} Hz, burst=${getFramesPerBurst()}, mode=$mode")
        } else {
            Log.w(TAG, "AAudio stream initialization failed at $targetRate Hz")
        }
        return success
    }

    @Synchronized
    fun start(): Boolean {
        if (!isLoaded || !isInitialized) return false
        return nativeStart()
    }

    @Synchronized
    fun pause(): Boolean {
        if (!isLoaded || !isInitialized) return false
        return nativePause()
    }

    @Synchronized
    fun flush(): Boolean {
        if (!isLoaded || !isInitialized) return false
        return nativeFlush()
    }

    @Synchronized
    fun stop(): Boolean {
        if (!isLoaded || !isInitialized) return false
        return nativeStop()
    }

    @Synchronized
    fun close() {
        if (isLoaded) {
            nativeClose()
        }
        isInitialized = false
    }

    fun write(data: FloatArray, offset: Int, numFrames: Int, timeoutNs: Long = 100_000_000L): Int {
        if (!isLoaded || !isInitialized || numFrames <= 0) return 0
        return nativeWrite(data, offset, numFrames, timeoutNs)
    }

    fun isExclusive(): Boolean {
        if (!isLoaded || !isInitialized) return false
        return nativeIsExclusive()
    }

    fun getSampleRate(): Int {
        if (!isLoaded || !isInitialized) return hardwareSampleRate
        return nativeGetSampleRate()
    }

    fun getFramesPerBurst(): Int {
        if (!isLoaded || !isInitialized) return hardwareFramesPerBurst
        return nativeGetFramesPerBurst()
    }

    fun getXRunCount(): Int {
        if (!isLoaded || !isInitialized) return 0
        return nativeGetXRunCount()
    }

    // External JNI fun declarations implemented in native-audio.cpp
    private external fun nativeInit(sampleRate: Int, channelCount: Int, framesPerBurst: Int): Boolean
    private external fun nativeStart(): Boolean
    private external fun nativePause(): Boolean
    private external fun nativeFlush(): Boolean
    private external fun nativeStop(): Boolean
    private external fun nativeClose()
    private external fun nativeWrite(data: FloatArray, offset: Int, numFrames: Int, timeoutNs: Long): Int
    private external fun nativeIsExclusive(): Boolean
    private external fun nativeGetSampleRate(): Int
    private external fun nativeGetFramesPerBurst(): Int
    private external fun nativeGetXRunCount(): Int
}
