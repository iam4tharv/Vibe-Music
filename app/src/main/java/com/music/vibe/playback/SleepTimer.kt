package com.music.vibe.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

/**
 * Sleep timer. Holds a deadline; [PlaybackService] watches it and pauses
 * playback once it passes.
 *
 * A deadline rather than a countdown: nothing has to tick for the timer to
 * stay accurate, so it survives the player UI being dismissed, and any
 * observer can work out how long is left for itself. elapsedRealtime, not
 * wall clock, so changing the system time can't cut a timer short.
 */
object SleepTimer {

    /** Deadline on [SystemClock.elapsedRealtime], or null when no timer is set. */
    val deadline = MutableStateFlow<Long?>(null)

    /** The duration in minutes that was chosen, so the picker can tick it. Null when off. */
    val minutes = MutableStateFlow<Int?>(null)

    /**
     * Pause when the current track ends instead of after a fixed wait.
     *
     * Deliberately not expressed as a deadline of "duration minus position":
     * seeking, crossfade and a queue that reorders itself would all leave that
     * number wrong, whereas the track ending is an event the player reports.
     */
    val afterTrack = MutableStateFlow(false)

    /** Durations offered in the preset picker. */
    val PRESETS = listOf(15, 30, 45, 60)

    /** Quick-jump durations offered as one-tap pills on the Custom Sleep Timer page. */
    val QUICK_CUSTOM_MINUTES = listOf(45, 60, 90, 120, 240, 480)

    /** Custom slider step and range (5 minutes to 12 hours in 5-minute steps). */
    const val STEP_MINUTES = 5
    const val MIN_MINUTES = STEP_MINUTES
    const val MAX_MINUTES = 720
    private const val STEP_COUNT = (MAX_MINUTES - MIN_MINUTES) / STEP_MINUTES

    /** Converts a slider fraction in `0f..1f` to a 5-minute-snapped duration in `5..720`. */
    fun minutesAt(fraction: Float): Int {
        val clamped = fraction.coerceIn(0f, 1f)
        val stepIndex = (clamped * STEP_COUNT).roundToInt()
        return (MIN_MINUTES + stepIndex * STEP_MINUTES).coerceIn(MIN_MINUTES, MAX_MINUTES)
    }

    /** Converts a duration in minutes (`5..720`) to its snapped slider fraction in `0f..1f`. */
    fun fractionOf(minutes: Int): Float {
        val clamped = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
        val stepIndex = ((clamped - MIN_MINUTES).toFloat() / STEP_MINUTES).roundToInt()
            .coerceIn(0, STEP_COUNT)
        return stepIndex.toFloat() / STEP_COUNT.toFloat()
    }

    /** Compact label for a quick-jump duration chip (`45m`, `1h`, `1h 30m`, `2h`, `4h`, `8h`). */
    fun formatQuickChipLabel(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    /** Formats remaining milliseconds into `h:mm:ss` (when >= 1h) or `m:ss`. */
    fun formatRemaining(remainingMs: Long): String {
        val totalSeconds = (remainingMs.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(java.util.Locale.ROOT, hours, minutes, seconds)
        } else {
            "%d:%02d".format(java.util.Locale.ROOT, minutes, seconds)
        }
    }

    /** Whether any kind of timer is currently armed. */
    val isRunning: Boolean get() = deadline.value != null || afterTrack.value

    fun start(minutes: Int) {
        afterTrack.value = false
        this.minutes.value = minutes
        deadline.value = SystemClock.elapsedRealtime() + minutes * 60_000L
    }

    /** Pause once the track playing right now finishes. */
    fun startAfterTrack() {
        minutes.value = null
        deadline.value = null
        afterTrack.value = true
    }

    fun cancel() {
        minutes.value = null
        deadline.value = null
        afterTrack.value = false
    }

    /** How long is left, or null when no timer is running. */
    fun remainingMs(): Long? =
        deadline.value?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L) }
}
