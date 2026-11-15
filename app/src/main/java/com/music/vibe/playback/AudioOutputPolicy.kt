package com.music.vibe.playback

import com.music.vibe.data.settings.OutputPcmMode

/**
 * Decides whether Media3 may open a PCM-float AudioTrack.
 *
 * High-bit-depth `ENCODING_PCM_FLOAT` output avoids 16-bit quantization noise and preserves
 * dynamic range during system volume scaling and Bluetooth codec encoding (LDAC, aptX HD, AAC).
 * Built-in phone speaker (`PHONE`) remains capped at 16-bit PCM for hardware safety on OEM mixers.
 */
internal object AudioOutputPolicy {
    /** Backwards-compatible USB-specific check. */
    fun shouldUseFloatOutput(
        requestedMode: OutputPcmMode,
        isPreferredUsbRoute: Boolean,
        advertisesPcmFloat: Boolean,
    ): Boolean = requestedMode == OutputPcmMode.FLOAT_32 &&
        isPreferredUsbRoute &&
        advertisesPcmFloat

    /**
     * Route-aware float output decision.
     *
     * - Built-in phone speaker (PHONE) remains capped at 16-bit to prevent OEM speaker protection
     *   DSP distortion on affected device paths.
     * - External routes (Bluetooth, Wired 3.5mm/aux, USB DAC, HDMI) use Float32 output when
     *   requested so AudioTrack preserves full 32-bit float headroom and avoids quantization noise.
     */
    fun shouldUseFloatOutput(
        requestedMode: OutputPcmMode,
        routeKind: AudioRouting.Kind,
        advertisesPcmFloat: Boolean,
    ): Boolean {
        if (requestedMode != OutputPcmMode.FLOAT_32) return false
        if (routeKind == AudioRouting.Kind.PHONE) return false
        return advertisesPcmFloat ||
            routeKind == AudioRouting.Kind.BLUETOOTH ||
            routeKind == AudioRouting.Kind.WIRED
    }

    /** Samsung's vendor FLAC decoder emits invalid timestamps with PCM float. */
    fun isUnsafeFloatFlacDecoder(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized == "c2.sec.flac.decoder" ||
            (normalized.startsWith("omx.sec.") && normalized.contains("flac"))
    }
}
