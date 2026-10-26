package com.music.vibe.ui.performance

import android.os.Build
import android.view.Display
import android.view.View
import android.view.Window
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MIN_PERFORMANCE_REFRESH_RATE = 50
private const val MAX_REASONABLE_REFRESH_RATE = 240

/** Refresh rates the current display can actually select, normalized for UI labels. */
fun Display?.supportedPerformanceRefreshRates(): List<Int> {
    val fallback = this?.refreshRate?.roundToInt()?.coerceAtLeast(MIN_PERFORMANCE_REFRESH_RATE) ?: 60
    return this?.supportedModes
        ?.asSequence()
        ?.map { it.refreshRate.roundToInt() }
        ?.filter { it in MIN_PERFORMANCE_REFRESH_RATE..MAX_REASONABLE_REFRESH_RATE }
        ?.distinct()
        ?.sorted()
        ?.toList()
        ?.ifEmpty { listOf(fallback) }
        ?: listOf(fallback)
}

/** Maps a saved preference to a real mode, including preferences restored on another device. */
fun Display?.resolvePerformanceRefreshRate(preferred: Int): Int =
    supportedPerformanceRefreshRates().minBy { abs(it - preferred) }

/** Finds the best Display.Mode for high refresh rate, matching the active resolution if possible. */
fun Display?.findBestPerformanceDisplayMode(targetRefreshRate: Int? = null): Display.Mode? {
    if (this == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
    val modes = supportedModes ?: return null
    if (modes.isEmpty()) return null

    val activeMode = runCatching { mode }.getOrNull()
    val candidateModes = if (activeMode != null) {
        val sameResolution = modes.filter {
            it.physicalWidth == activeMode.physicalWidth && it.physicalHeight == activeMode.physicalHeight
        }
        sameResolution.ifEmpty { modes.toList() }
    } else {
        modes.toList()
    }

    return if (targetRefreshRate != null && targetRefreshRate > 0) {
        // Find mode closest to target refresh rate; tie-breaker: higher refresh rate
        candidateModes
            .sortedWith(
                compareBy<Display.Mode> { abs(it.refreshRate.roundToInt() - targetRefreshRate) }
                    .thenByDescending { it.refreshRate }
            )
            .firstOrNull()
            ?: candidateModes.maxByOrNull { it.refreshRate }
    } else {
        candidateModes.maxByOrNull { it.refreshRate }
    }
}

/**
 * Applies high refresh rate preferences to any Window (Activity or ModalBottomSheet DialogWindow)
 * and hints Android 15+ frame rate boosting on the target View.
 */
fun applyWindowPerformanceMode(
    window: Window,
    enabled: Boolean,
    refreshRate: Int,
    targetView: View,
) {
    try {
        val params = window.attributes
        if (enabled) {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                targetView.context.display ?: @Suppress("DEPRECATION") window.windowManager.defaultDisplay
            } else {
                @Suppress("DEPRECATION")
                window.windowManager.defaultDisplay
            }
            val bestMode = display.findBestPerformanceDisplayMode(targetRefreshRate = refreshRate)
            val resolvedRate = bestMode?.refreshRate ?: refreshRate.toFloat()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && bestMode != null) {
                params.preferredDisplayModeId = bestMode.modeId
            }
            params.preferredRefreshRate = resolvedRate
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                params.preferredDisplayModeId = 0
            }
            params.preferredRefreshRate = 0f
        }
        window.attributes = params

        if (Build.VERSION.SDK_INT >= 35) {
            runCatching {
                targetView.requestedFrameRate = if (enabled) {
                    View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
                } else {
                    View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
                }
            }
        }
    } catch (_: Throwable) {
        // Defensive catch for OEM WindowManager quirks
    }
}

