package com.music.vibe.data.firebase

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.music.vibe.data.TrackLog

/**
 * Manages Firebase Analytics telemetry for Vibe Music.
 *
 * Captures playback statistics, codec usage (e.g. FLAC Lossless vs AAC vs Opus),
 * and audio pipeline stability metrics.
 */
object FirebaseAnalyticsManager {

    private const val TAG = "VibeMusic"
    private var analytics: FirebaseAnalytics? = null

    fun init(context: Context) {
        if (analytics != null) return
        runCatching {
            analytics = FirebaseAnalytics.getInstance(context)
            TrackLog.d(TAG, "FirebaseAnalytics initialized")
        }.onFailure {
            TrackLog.w(TAG, "FirebaseAnalytics init failed: ${it.message}")
        }
    }

    fun logTrackPlayed(
        title: String,
        artist: String,
        source: String,
        codec: String?,
        isLossless: Boolean,
    ) {
        val bundle = Bundle().apply {
            putString("track_title", title.take(100))
            putString("artist", artist.take(100))
            putString("source", source)
            putString("codec", codec ?: "unknown")
            putBoolean("is_lossless", isLossless)
        }
        logEvent("track_played", bundle)
    }

    fun logSourceSelected(sourceName: String, enabled: Boolean) {
        val bundle = Bundle().apply {
            putString("source_name", sourceName)
            putBoolean("enabled", enabled)
        }
        logEvent("source_selected", bundle)
    }

    fun logQualityTierChanged(qualityName: String) {
        val bundle = Bundle().apply {
            putString("quality_tier", qualityName)
        }
        logEvent("quality_tier_changed", bundle)
    }

    private fun logEvent(eventName: String, params: Bundle) {
        runCatching {
            analytics?.logEvent(eventName, params)
        }.onFailure {
            TrackLog.w(TAG, "Failed to log event '$eventName': ${it.message}")
        }
    }
}
