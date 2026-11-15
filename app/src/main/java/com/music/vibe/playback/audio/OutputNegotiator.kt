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

import android.media.AudioFormat
import com.music.vibe.data.settings.OutputPcmMode
import com.music.vibe.data.settings.UsbTransportMode
import com.music.vibe.playback.AudioOutputPolicy
import com.music.vibe.playback.AudioRouting
import com.music.vibe.playback.audio.bluetooth.BluetoothTelemetry
import com.music.vibe.playback.audio.usb.DirectUsbProbeResult

/**
 * Output transport mechanism selected by the universal best-path engine.
 */
enum class TransportType(val label: String) {
    AUDIO_TRACK("AudioTrack"),
    AUDIO_TRACK_DIRECT("AudioTrack (Direct)"),
    BIT_PERFECT_USB("USB Bit-Perfect (API 34)"),
    DIRECT_USB("Direct USB Driver"),
    AAUDIO_EXCLUSIVE("AAudio (Exclusive MMAP)"),
    AAUDIO_SHARED("AAudio (Low-Latency)"),
}

/**
 * Authoritative rationale when the audio pipeline falls back from the listener's requested
 * format or transport to a lower-precision or system-mixed path.
 */
enum class FallbackReason(val label: String) {
    NONE("None"),
    UNSUPPORTED_FORMAT("Unsupported source format"),
    ROUTE_LIMITATION("Route limitation (device profile)"),
    OS_LIMITATION("OS limitation (system audio policy)"),
    DIRECT_USB_UNAVAILABLE("Direct USB unavailable"),
    DECODER_LIMITATION("Decoder limitation"),
}

data class SourceDescriptor(
    val encoding: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitDepth: Int?,
)

data class DecoderDescriptor(
    val name: String?,
    val encoding: String = "Float32",
    val sampleRateHz: Int,
    val channelCount: Int,
)

data class DspDescriptor(
    val format: String = "Float32",
    val sampleRateHz: Int,
    val channelCount: Int,
)

data class RouteDescriptor(
    val kind: AudioRouting.Kind,
    val deviceName: String,
    val isDirectUsbCapable: Boolean = false,
    val advertisedEncodings: List<Int> = emptyList(),
    val advertisedSampleRates: List<Int> = emptyList(),
    val directSupport: DirectAudioProbe.DirectSupport = DirectAudioProbe.DirectSupport.NONE,
    val bluetoothTelemetry: BluetoothTelemetry? = null,
)

data class OutputDescriptor(
    val transport: TransportType,
    val encoding: PcmEncoding,
    val sampleRateHz: Int,
    val channelCount: Int,
    val isDirect: Boolean = false,
    val systemMixerRateHz: Int? = null,
    val fallbackReason: FallbackReason = FallbackReason.NONE,
    val fallbackDetail: String? = null,
)

/**
 * Complete, authoritative snapshot of the 6-layer audio pipeline state:
 * Source -> Decoder -> DSP -> Route -> Output -> System.
 */
data class OutputNegotiationResult(
    val source: SourceDescriptor,
    val decoder: DecoderDescriptor,
    val dsp: DspDescriptor,
    val route: RouteDescriptor,
    val output: OutputDescriptor,
) {
    /** True if end-to-end sample rate was preserved without downstream mixer conversion. */
    val isSampleRatePreserved: Boolean
        get() = output.isDirect || output.systemMixerRateHz == null || output.sampleRateHz == output.systemMixerRateHz
}

/**
 * Universal Best-Path Output Selection Engine.
 *
 * Deterministic selection order:
 * 1. Android 14+ (API 34) Official `MIXER_BEHAVIOR_BIT_PERFECT` USB transport when supported and preferred.
 * 2. Custom Userspace Direct USB driver (UAC 1.0 / UAC 2.0) when selected or as fallback on Android <= 13.
 * 3. Native/direct playback when Android exposes a direct path for the exact format/rate/channel configuration.
 * 4. High-bit-depth Float32 / 24-bit AudioTrack matched to Bluetooth or internal DAC native sample rate.
 * 5. Safe 16-bit fallback.
 */
object OutputNegotiator {

    fun negotiate(
        source: SourceDescriptor,
        decoderName: String?,
        decoderEncoding: String = "Float32",
        sampleRateHz: Int,
        targetSampleRateHz: Int = sampleRateHz,
        channelCount: Int,
        routeKind: AudioRouting.Kind,
        deviceName: String,
        advertisedEncodings: List<Int>,
        advertisedSampleRates: List<Int>,
        requestedMode: OutputPcmMode,
        usbTransportMode: UsbTransportMode = UsbTransportMode.AUTO,
        directUsbProbe: DirectUsbProbeResult? = null,
        directSupport: DirectAudioProbe.DirectSupport = DirectAudioProbe.DirectSupport.NONE,
        bluetoothTelemetry: BluetoothTelemetry? = null,
        delegateSupportsFloat: Boolean = true,
        delegateSupportsPcm24: Boolean = true,
        knownSystemMixerRateHz: Int? = null,
    ): OutputNegotiationResult {
        val decoder = DecoderDescriptor(
            name = decoderName,
            encoding = decoderEncoding,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
        )

        val dsp = DspDescriptor(
            format = "Float32",
            sampleRateHz = targetSampleRateHz,
            channelCount = channelCount,
        )

        val route = RouteDescriptor(
            kind = routeKind,
            deviceName = deviceName,
            isDirectUsbCapable = directUsbProbe?.isViable == true,
            advertisedEncodings = advertisedEncodings,
            advertisedSampleRates = advertisedSampleRates,
            directSupport = directSupport,
            bluetoothTelemetry = bluetoothTelemetry,
        )

        val output = selectBestOutput(
            source = source,
            sampleRateHz = targetSampleRateHz,
            channelCount = channelCount,
            routeKind = routeKind,
            advertisedEncodings = advertisedEncodings,
            advertisedSampleRates = advertisedSampleRates,
            requestedMode = requestedMode,
            usbTransportMode = usbTransportMode,
            directUsbProbe = directUsbProbe,
            directSupport = directSupport,
            bluetoothTelemetry = bluetoothTelemetry,
            delegateSupportsFloat = delegateSupportsFloat,
            delegateSupportsPcm24 = delegateSupportsPcm24,
            knownSystemMixerRateHz = knownSystemMixerRateHz,
        )

        return OutputNegotiationResult(
            source = source,
            decoder = decoder,
            dsp = dsp,
            route = route,
            output = output,
        )
    }

    private fun selectBestOutput(
        source: SourceDescriptor,
        sampleRateHz: Int,
        channelCount: Int,
        routeKind: AudioRouting.Kind,
        advertisedEncodings: List<Int>,
        advertisedSampleRates: List<Int>,
        requestedMode: OutputPcmMode,
        usbTransportMode: UsbTransportMode,
        directUsbProbe: DirectUsbProbeResult?,
        directSupport: DirectAudioProbe.DirectSupport,
        bluetoothTelemetry: BluetoothTelemetry?,
        delegateSupportsFloat: Boolean,
        delegateSupportsPcm24: Boolean,
        knownSystemMixerRateHz: Int?,
    ): OutputDescriptor {
        val advertisesFloat = advertisedEncodings.contains(AudioFormat.ENCODING_PCM_FLOAT)
        val advertisesPcm24 = advertisedEncodings.contains(AudioFormat.ENCODING_PCM_24BIT_PACKED)

        // 1. Phone Speaker Safety Rule: Always cap at PCM16
        if (routeKind == AudioRouting.Kind.PHONE) {
            val fallbackReason = if (requestedMode == OutputPcmMode.FLOAT_32) {
                FallbackReason.ROUTE_LIMITATION
            } else {
                FallbackReason.NONE
            }
            val fallbackDetail = if (fallbackReason != FallbackReason.NONE) {
                "Speaker output capped at 16-bit PCM to prevent OEM mixer distortion"
            } else {
                null
            }
            return OutputDescriptor(
                transport = TransportType.AUDIO_TRACK,
                encoding = PcmEncoding.PCM_16BIT,
                sampleRateHz = sampleRateHz,
                channelCount = channelCount,
                isDirect = false,
                systemMixerRateHz = knownSystemMixerRateHz,
                fallbackReason = fallbackReason,
                fallbackDetail = fallbackDetail,
            )
        }

        // 2. USB DAC Transport Negotiation (Android 14+ Bit-Perfect API vs Custom Userspace USB Driver)
        if (routeKind == AudioRouting.Kind.USB) {
            val bitPerfectApiAvailable = directSupport.isBitPerfectMixerSupported || directSupport.isBitPerfectMixerActive
            val customDriverViable = directUsbProbe?.isViable == true

            val useBitPerfectApi = when (usbTransportMode) {
                UsbTransportMode.AUTO -> bitPerfectApiAvailable
                UsbTransportMode.ANDROID_14_MIXER -> bitPerfectApiAvailable
                UsbTransportMode.CUSTOM_USB_DRIVER -> false
            }

            if (useBitPerfectApi) {
                val bpEncoding = when {
                    requestedMode == OutputPcmMode.FLOAT_32 && delegateSupportsFloat -> PcmEncoding.PCM_FLOAT
                    directSupport.bitPerfectEncoding == AudioFormat.ENCODING_PCM_FLOAT && delegateSupportsFloat -> PcmEncoding.PCM_FLOAT
                    else -> PcmEncoding.PCM_16BIT
                }
                val bpRate = directSupport.bitPerfectSampleRateHz ?: sampleRateHz
                return OutputDescriptor(
                    transport = TransportType.BIT_PERFECT_USB,
                    encoding = bpEncoding,
                    sampleRateHz = bpRate,
                    channelCount = channelCount,
                    isDirect = true,
                    systemMixerRateHz = null, // Bypasses AudioFlinger, volume scaling, and resampling
                    fallbackReason = FallbackReason.NONE,
                    fallbackDetail = null,
                )
            }

            val useCustomDriver = when (usbTransportMode) {
                UsbTransportMode.AUTO -> !bitPerfectApiAvailable && customDriverViable
                UsbTransportMode.CUSTOM_USB_DRIVER -> customDriverViable
                UsbTransportMode.ANDROID_14_MIXER -> false
            }

            if (useCustomDriver) {
                return OutputDescriptor(
                    transport = TransportType.DIRECT_USB,
                    encoding = PcmEncoding.PCM_FLOAT,
                    sampleRateHz = sampleRateHz,
                    channelCount = channelCount,
                    isDirect = true,
                    systemMixerRateHz = null, // Bypasses Android AudioFlinger entirely
                    fallbackReason = FallbackReason.NONE,
                    fallbackDetail = null,
                )
            }
        }

        // 3. Android DIRECT Playback Evaluation (API 33+)
        // Priority 3A: Direct Float32
        if (requestedMode == OutputPcmMode.FLOAT_32 && directSupport.supportsFloat && delegateSupportsFloat) {
            return OutputDescriptor(
                transport = TransportType.AUDIO_TRACK_DIRECT,
                encoding = PcmEncoding.PCM_FLOAT,
                sampleRateHz = sampleRateHz,
                channelCount = channelCount,
                isDirect = true,
                systemMixerRateHz = null,
                fallbackReason = FallbackReason.NONE,
                fallbackDetail = null,
            )
        }

        // Priority 3B: Direct PCM24
        val isSourceHighRes = (source.bitDepth ?: 16) > 16
        if (requestedMode != OutputPcmMode.PCM_16 && isSourceHighRes && directSupport.supportsPcm24 && delegateSupportsPcm24) {
            val fallbackReason = if (requestedMode == OutputPcmMode.FLOAT_32 && !directSupport.supportsFloat) {
                FallbackReason.ROUTE_LIMITATION
            } else {
                FallbackReason.NONE
            }
            val fallbackDetail = if (fallbackReason != FallbackReason.NONE) {
                "Route exposes direct 24-bit PCM (Float32 converted to packed 24-bit)"
            } else {
                null
            }
            return OutputDescriptor(
                transport = TransportType.AUDIO_TRACK_DIRECT,
                encoding = PcmEncoding.PCM_24BIT_PACKED,
                sampleRateHz = sampleRateHz,
                channelCount = channelCount,
                isDirect = true,
                systemMixerRateHz = null,
                fallbackReason = fallbackReason,
                fallbackDetail = fallbackDetail,
            )
        }

        // Priority 3C: Direct PCM16
        if (directSupport.supportsPcm16) {
            val fallbackReason = when {
                requestedMode == OutputPcmMode.PCM_16 -> FallbackReason.NONE
                requestedMode == OutputPcmMode.FLOAT_32 -> FallbackReason.ROUTE_LIMITATION
                isSourceHighRes -> FallbackReason.ROUTE_LIMITATION
                else -> FallbackReason.NONE
            }
            val fallbackDetail = when {
                requestedMode == OutputPcmMode.FLOAT_32 ->
                    "Route does not expose direct Float32 or 24-bit PCM (using direct 16-bit PCM)"
                isSourceHighRes ->
                    "Route does not expose direct 24-bit PCM (using direct 16-bit PCM)"
                else -> null
            }
            return OutputDescriptor(
                transport = TransportType.AUDIO_TRACK_DIRECT,
                encoding = PcmEncoding.PCM_16BIT,
                sampleRateHz = sampleRateHz,
                channelCount = channelCount,
                isDirect = true,
                systemMixerRateHz = null,
                fallbackReason = fallbackReason,
                fallbackDetail = fallbackDetail,
            )
        }

        // 4. External Route High-Bit-Depth Capabilities (Bluetooth / Wired / USB AudioTrack)
        val canUseFloat = AudioOutputPolicy.shouldUseFloatOutput(requestedMode, routeKind, advertisesFloat) &&
            delegateSupportsFloat
        if (canUseFloat) {
            return OutputDescriptor(
                transport = TransportType.AUDIO_TRACK,
                encoding = PcmEncoding.PCM_FLOAT,
                sampleRateHz = sampleRateHz,
                channelCount = channelCount,
                isDirect = false,
                systemMixerRateHz = knownSystemMixerRateHz,
                fallbackReason = FallbackReason.NONE,
                fallbackDetail = null,
            )
        }

        if (requestedMode != OutputPcmMode.PCM_16 && isSourceHighRes && advertisesPcm24 && delegateSupportsPcm24) {
            val fallbackReason = if (requestedMode == OutputPcmMode.FLOAT_32) {
                FallbackReason.ROUTE_LIMITATION
            } else {
                FallbackReason.NONE
            }
            val fallbackDetail = if (fallbackReason != FallbackReason.NONE) {
                "${routeKind.name} route advertises 24-bit PCM (Float32 converted to packed 24-bit)"
            } else {
                null
            }
            return OutputDescriptor(
                transport = TransportType.AUDIO_TRACK,
                encoding = PcmEncoding.PCM_24BIT_PACKED,
                sampleRateHz = sampleRateHz,
                channelCount = channelCount,
                isDirect = false,
                systemMixerRateHz = knownSystemMixerRateHz,
                fallbackReason = fallbackReason,
                fallbackDetail = fallbackDetail,
            )
        }

        // 5. 16-bit PCM Fallback
        val (reason, detail) = when {
            routeKind == AudioRouting.Kind.USB -> {
                val r = if (directUsbProbe?.isViable == false) {
                    FallbackReason.DIRECT_USB_UNAVAILABLE
                } else {
                    FallbackReason.ROUTE_LIMITATION
                }
                val d = directUsbProbe?.diagnosticReason
                    ?: "USB device advertises 16-bit PCM only"
                Pair(r, d)
            }
            routeKind == AudioRouting.Kind.BLUETOOTH -> {
                val btSummary = bluetoothTelemetry?.codecName?.let { " ($it)" }.orEmpty()
                Pair(
                    FallbackReason.ROUTE_LIMITATION,
                    "Bluetooth route$btSummary advertises 16-bit PCM only",
                )
            }
            requestedMode == OutputPcmMode.FLOAT_32 -> {
                Pair(
                    FallbackReason.ROUTE_LIMITATION,
                    "${routeKind.name} route advertises 16-bit PCM only",
                )
            }
            isSourceHighRes && requestedMode != OutputPcmMode.PCM_16 -> {
                Pair(
                    FallbackReason.ROUTE_LIMITATION,
                    "${routeKind.name} route does not expose high-res output",
                )
            }
            else -> {
                Pair(FallbackReason.NONE, null)
            }
        }

        return OutputDescriptor(
            transport = TransportType.AUDIO_TRACK,
            encoding = PcmEncoding.PCM_16BIT,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            isDirect = false,
            systemMixerRateHz = knownSystemMixerRateHz,
            fallbackReason = reason,
            fallbackDetail = detail,
        )
    }
}
