/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.echo.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.music.echo.playback.MusicService

class TurntableWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Only trigger update through MusicService if it's already running
        // This prevents BackgroundServiceStartNotAllowedException on Android 14+
        if (MusicService.isRunning) {
            val intent = Intent(context, MusicService::class.java).apply {
                action = ACTION_UPDATE_TURNTABLE_WIDGET
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Service might be restricted in background
                timber.log.Timber.e(e, "Service restricted")
            }
        }
        // If service is not running, widget shows default layout until user opens app
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Custom actions are now handled securely by WidgetActionReceiver
    }

    companion object {
        const val ACTION_TURNTABLE_PLAY_PAUSE = "com.music.echo.widget.TURNTABLE_PLAY_PAUSE"
        const val ACTION_TURNTABLE_NEXT = "com.music.echo.widget.TURNTABLE_NEXT"
        const val ACTION_TURNTABLE_PREVIOUS = "com.music.echo.widget.TURNTABLE_PREVIOUS"
        const val ACTION_UPDATE_TURNTABLE_WIDGET = "com.music.echo.widget.UPDATE_TURNTABLE_WIDGET"
    }
}
