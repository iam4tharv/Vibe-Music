/*
 * Copyright (C) 2026 Vibe Music Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.music.vibe.playback.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.music.vibe.playback.AudioRouting

/**
 * Live capability probe and Android 14+ Bit-Perfect Mixer controller.
 *
 * - Queries Android 13+ (API 33+) [AudioManager.getDirectPlaybackSupport] and
 *   [AudioManager.getDirectProfilesForAttributes].
 * - Queries and activates Android 14+ (API 34+) [AudioManager.getSupportedMixerAttributes] and
 *   [AudioManager.setPreferredMixerAttributes] with [AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT]
 *   to route audio streams directly to a USB DAC, bypassing AudioFlinger, volume scaling, and resampling.
 * - Resolves native output sample rates for Bluetooth devices (`AudioDeviceInfo.getSampleRates()`)
 *   and internal DACs (`AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE`) so the in-app high-precision
 *   resampler can match the exact hardware rate.
 */
object DirectAudioProbe {

    private const val TAG = "DirectAudioProbe"

    data class DirectSupport(
        val isDirectSupported: Boolean,
        val isOffloadSupported: Boolean,
        val supportsFloat: Boolean,
        val supportsPcm24: Boolean,
        val supportsPcm16: Boolean,
        val isBitPerfectMixerSupported: Boolean = false,
        val isBitPerfectMixerActive: Boolean = false,
        val bitPerfectEncoding: Int? = null,
        val bitPerfectSampleRateHz: Int? = null,
        val directProfiles: List<DirectProfileInfo> = emptyList(),
        val description: String,
    ) {
        companion object {
            val NONE = DirectSupport(
                isDirectSupported = false,
                isOffloadSupported = false,
                supportsFloat = false,
                supportsPcm24 = false,
                supportsPcm16 = false,
                isBitPerfectMixerSupported = false,
                isBitPerfectMixerActive = false,
                description = "Direct playback not supported or API < 33",
            )
        }
    }

    data class DirectProfileInfo(
        val format: Int,
        val sampleRates: List<Int>,
        val channelMasks: List<Int>,
    )

    private val mediaAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    @Volatile
    private var lastConfiguredBitPerfectDeviceId: Int? = null

    /**
     * Evaluates direct playback support and Android 14+ Bit-Perfect mixer support for the specified
     * audio stream parameters on the active route.
     */
    fun probeDirectSupport(
        audioManager: AudioManager?,
        sampleRateHz: Int,
        channelCount: Int,
        activeDevice: AudioDeviceInfo? = null,
        applyBitPerfectMixerIfSupported: Boolean = false,
    ): DirectSupport {
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return DirectSupport.NONE
        }

        return try {
            probeDirectSupportApi33(
                audioManager = audioManager,
                sampleRateHz = sampleRateHz,
                channelCount = channelCount,
                activeDevice = activeDevice,
                applyBitPerfectMixer = applyBitPerfectMixerIfSupported,
            )
        } catch (_: Throwable) {
            DirectSupport.NONE
        }
    }

    /**
     * Resolves the optimal native sample rate for [activeDevice] and [routeKind]:
     * - **USB DAC**: Keeps [sourceSampleRateHz] if supported by the DAC (or empty rate list = arbitrary),
     *   otherwise picks the exact integer multiple or highest supported rate.
     * - **Bluetooth**: Queries [AudioDeviceInfo.getSampleRates] (from `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`)
     *   and [bluetoothCodecSampleRateHz] so our in-app high-precision resampler matches the Bluetooth
     *   device's exact native rate, preventing Android's lower-quality system resampler from touching the stream.
     * - **Wired / Internal Speaker**: Uses [hardwareSampleRateHz] (`AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE`)
     *   unless a direct hardware profile natively supports [sourceSampleRateHz].
     */
    fun resolveOptimalTargetSampleRate(
        sourceSampleRateHz: Int,
        routeKind: AudioRouting.Kind,
        activeDevice: AudioDeviceInfo?,
        bluetoothCodecSampleRateHz: Int? = null,
        hardwareSampleRateHz: Int = 48000,
        isDirectActive: Boolean = false,
    ): Int {
        if (sourceSampleRateHz <= 0) return hardwareSampleRateHz.takeIf { it > 0 } ?: 48000
        val deviceRates = activeDevice?.sampleRates?.filter { it > 0 } ?: emptyList()

        return when (routeKind) {
            AudioRouting.Kind.USB -> {
                if (deviceRates.isEmpty() || deviceRates.contains(sourceSampleRateHz)) {
                    sourceSampleRateHz
                } else {
                    // Prefer an exact integer multiple (e.g. 44.1k -> 88.2k or 48k -> 96k) if available
                    deviceRates.filter { it >= sourceSampleRateHz && it % sourceSampleRateHz == 0 }
                        .minOrNull()
                        ?: deviceRates.minByOrNull { kotlin.math.abs(it - sourceSampleRateHz) }
                        ?: sourceSampleRateHz
                }
            }
            AudioRouting.Kind.BLUETOOTH -> {
                // 1. If Bluetooth A2DP/LE codec telemetry reports the active negotiated air rate, match it
                if (bluetoothCodecSampleRateHz != null && bluetoothCodecSampleRateHz > 0) {
                    if (deviceRates.isEmpty() || deviceRates.contains(bluetoothCodecSampleRateHz)) {
                        return bluetoothCodecSampleRateHz
                    }
                }
                // 2. Query AudioDeviceInfo.getSampleRates() from the connected Bluetooth output device
                if (deviceRates.isNotEmpty()) {
                    if (deviceRates.contains(sourceSampleRateHz)) {
                        sourceSampleRateHz
                    } else {
                        // Prefer integer multiple, then highest hi-res rate <= 96kHz, or closest rate
                        deviceRates.filter { it >= sourceSampleRateHz && it % sourceSampleRateHz == 0 }
                            .minOrNull()
                            ?: deviceRates.maxOrNull()
                            ?: hardwareSampleRateHz
                    }
                } else {
                    hardwareSampleRateHz.takeIf { it > 0 } ?: 48000
                }
            }
            AudioRouting.Kind.WIRED, AudioRouting.Kind.HDMI -> {
                if (isDirectActive && (deviceRates.isEmpty() || deviceRates.contains(sourceSampleRateHz))) {
                    sourceSampleRateHz
                } else if (deviceRates.isNotEmpty() && !deviceRates.contains(sourceSampleRateHz)) {
                    deviceRates.filter { it >= sourceSampleRateHz && it % sourceSampleRateHz == 0 }
                        .minOrNull()
                        ?: deviceRates.minByOrNull { kotlin.math.abs(it - sourceSampleRateHz) }
                        ?: (hardwareSampleRateHz.takeIf { it > 0 } ?: 48000)
                } else {
                    hardwareSampleRateHz.takeIf { it > 0 } ?: 48000
                }
            }
            AudioRouting.Kind.PHONE, AudioRouting.Kind.OTHER -> {
                hardwareSampleRateHz.takeIf { it > 0 } ?: 48000
            }
        }
    }

    /**
     * Clears any previously set Android 14+ preferred mixer attributes when leaving bit-perfect mode
     * or disconnecting a USB DAC.
     */
    fun clearBitPerfectMixerAttributes(audioManager: AudioManager?, device: AudioDeviceInfo? = null) {
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        try {
            clearBitPerfectMixerApi34(audioManager, device)
        } catch (_: Throwable) {
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun clearBitPerfectMixerApi34(audioManager: AudioManager, device: AudioDeviceInfo?) {
        val targetDevices = if (device != null) {
            listOf(device)
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            }
        }
        for (dev in targetDevices) {
            runCatching {
                audioManager.clearPreferredMixerAttributes(mediaAudioAttributes, dev)
            }
        }
        lastConfiguredBitPerfectDeviceId = null
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun probeDirectSupportApi33(
        audioManager: AudioManager,
        sampleRateHz: Int,
        channelCount: Int,
        activeDevice: AudioDeviceInfo?,
        applyBitPerfectMixer: Boolean,
    ): DirectSupport {
        val channelMask = if (channelCount == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }

        val attributes = mediaAudioAttributes

        // 1. Inspect all direct profiles for media attributes
        val directProfilesList = mutableListOf<DirectProfileInfo>()
        try {
            val profiles = audioManager.getDirectProfilesForAttributes(attributes)
            for (p in profiles) {
                directProfilesList.add(
                    DirectProfileInfo(
                        format = p.format,
                        sampleRates = p.sampleRates.toList(),
                        channelMasks = p.channelMasks.toList(),
                    ),
                )
            }
        } catch (_: Throwable) {
        }

        // 2. Query exact support for Float32, PCM24, and PCM16
        val floatFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRateHz)
            .setChannelMask(channelMask)
            .build()
        val floatSupport = AudioManager.getDirectPlaybackSupport(floatFormat, attributes)
        var supportsFloatDirect = (floatSupport != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED)

        val pcm24Format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_24BIT_PACKED)
            .setSampleRate(sampleRateHz)
            .setChannelMask(channelMask)
            .build()
        val pcm24Support = AudioManager.getDirectPlaybackSupport(pcm24Format, attributes)
        var supportsPcm24Direct = (pcm24Support != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED)

        val pcm16Format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRateHz)
            .setChannelMask(channelMask)
            .build()
        val pcm16Support = AudioManager.getDirectPlaybackSupport(pcm16Format, attributes)
        var supportsPcm16Direct = (pcm16Support != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED)

        // 3. On Android 14+ (API 34+), query and optionally configure MIXER_BEHAVIOR_BIT_PERFECT for USB DACs
        var bitPerfectSupported = false
        var bitPerfectActive = false
        var bitPerfectEncoding: Int? = null
        var bitPerfectSampleRate: Int? = null

        val isUsbDevice = activeDevice?.type in setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && activeDevice != null && isUsbDevice) {
            val mixerProbe = probeAndConfigureBitPerfectApi34(
                audioManager = audioManager,
                usbDevice = activeDevice,
                desiredSampleRateHz = sampleRateHz,
                channelMask = channelMask,
                applyMixerAttributes = applyBitPerfectMixer,
            )
            bitPerfectSupported = mixerProbe.supported
            bitPerfectActive = mixerProbe.active
            bitPerfectEncoding = mixerProbe.encoding
            bitPerfectSampleRate = mixerProbe.sampleRateHz

            if (bitPerfectSupported) {
                when (bitPerfectEncoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> supportsFloatDirect = true
                    AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_32BIT -> supportsPcm24Direct = true
                    AudioFormat.ENCODING_PCM_16BIT -> supportsPcm16Direct = true
                }
            }
        }

        val anyDirect = supportsFloatDirect || supportsPcm24Direct || supportsPcm16Direct || bitPerfectSupported
        val anyOffload = (floatSupport and AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED) != 0 ||
            (pcm24Support and AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED) != 0 ||
            (pcm16Support and AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED) != 0

        val description = buildString {
            if (bitPerfectActive) {
                append("Android 14+ Bit-Perfect USB Mixer active @ ${bitPerfectSampleRate ?: sampleRateHz} Hz")
            } else if (bitPerfectSupported) {
                append("Android 14+ Bit-Perfect USB Mixer supported @ ${bitPerfectSampleRate ?: sampleRateHz} Hz")
            } else if (anyDirect) {
                append("Direct PCM supported: ")
                val formats = mutableListOf<String>()
                if (supportsFloatDirect) formats.add("Float32")
                if (supportsPcm24Direct) formats.add("24-bit")
                if (supportsPcm16Direct) formats.add("16-bit")
                append(formats.joinToString("/"))
                append(" @ $sampleRateHz Hz")
            } else {
                append("Direct PCM not exposed for $sampleRateHz Hz (AudioFlinger mixer route)")
            }
        }

        return DirectSupport(
            isDirectSupported = anyDirect,
            isOffloadSupported = anyOffload,
            supportsFloat = supportsFloatDirect,
            supportsPcm24 = supportsPcm24Direct,
            supportsPcm16 = supportsPcm16Direct,
            isBitPerfectMixerSupported = bitPerfectSupported,
            isBitPerfectMixerActive = bitPerfectActive,
            bitPerfectEncoding = bitPerfectEncoding,
            bitPerfectSampleRateHz = bitPerfectSampleRate,
            directProfiles = directProfilesList,
            description = description,
        )
    }

    private data class BitPerfectMixerResult(
        val supported: Boolean = false,
        val active: Boolean = false,
        val encoding: Int? = null,
        val sampleRateHz: Int? = null,
    )

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun probeAndConfigureBitPerfectApi34(
        audioManager: AudioManager,
        usbDevice: AudioDeviceInfo,
        desiredSampleRateHz: Int,
        channelMask: Int,
        applyMixerAttributes: Boolean,
    ): BitPerfectMixerResult {
        return try {
            val supportedAttrs = audioManager.getSupportedMixerAttributes(usbDevice)
            val bitPerfectCandidates = supportedAttrs.filter {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
            }
            if (bitPerfectCandidates.isEmpty()) {
                return BitPerfectMixerResult()
            }

            // Pick the best matching bit-perfect AudioMixerAttributes:
            // 1. Exact sample rate + exact channel mask + highest bit depth (Float32 > 32-bit > 24-bit > 16-bit)
            // 2. Closest sample rate if exact sample rate isn't in mixer attributes
            val selected = bitPerfectCandidates
                .sortedWith(
                    compareByDescending<AudioMixerAttributes> { it.format.sampleRate == desiredSampleRateHz }
                        .thenByDescending { it.format.channelMask == channelMask }
                        .thenByDescending { encodingPriority(it.format.encoding) }
                        .thenBy { kotlin.math.abs(it.format.sampleRate - desiredSampleRateHz) },
                )
                .first()

            var isNowActive = false
            if (applyMixerAttributes) {
                val applied = audioManager.setPreferredMixerAttributes(
                    mediaAudioAttributes,
                    usbDevice,
                    selected,
                )
                isNowActive = applied
                if (applied) {
                    lastConfiguredBitPerfectDeviceId = usbDevice.id
                    Log.i(
                        TAG,
                        "Activated Android 14+ MIXER_BEHAVIOR_BIT_PERFECT on ${usbDevice.productName}: " +
                            "sr=${selected.format.sampleRate} enc=${selected.format.encoding}",
                    )
                }
            } else {
                val currentPreferred = audioManager.getPreferredMixerAttributes(mediaAudioAttributes, usbDevice)
                isNowActive = currentPreferred?.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
            }

            BitPerfectMixerResult(
                supported = true,
                active = isNowActive,
                encoding = selected.format.encoding,
                sampleRateHz = selected.format.sampleRate.takeIf { it > 0 } ?: desiredSampleRateHz,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Android 14+ bit-perfect mixer attribute query failed: ${t.message}")
            BitPerfectMixerResult()
        }
    }

    private fun encodingPriority(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        AudioFormat.ENCODING_PCM_32BIT -> 3
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 2
        AudioFormat.ENCODING_PCM_16BIT -> 1
        else -> 0
    }
}
