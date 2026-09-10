package com.music.echo.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.music.echo.playback.MusicService
import java.io.File
import com.music.echo.widget.MusicRecognizerWidgetService.Companion.PREFS_NAME
import com.music.echo.widget.MusicRecognizerWidgetService.Companion.PREF_STATE
import com.music.echo.widget.MusicRecognizerWidgetService.Companion.PREF_SONG_TITLE
import com.music.echo.widget.MusicRecognizerWidgetService.Companion.PREF_ARTIST_NAME
import com.music.echo.widget.MusicRecognizerWidgetService.Companion.PREF_ERROR_MESSAGE
import com.music.echo.widget.MusicRecognizerWidgetService.Companion.PREF_COVER_ART_PATH
import com.music.echo.widget.MusicRecognizerWidgetService.Companion.PREF_PULSE_FRAME
import com.music.echo.widget.MusicRecognizerWidgetService.Companion.STATE_IDLE
import com.music.echo.widget.MusicRecognizerWidgetService.Companion.ALBUM_ART_CACHE_FILE

/**
 * A non-exported BroadcastReceiver to handle custom actions from our AppWidgets securely.
 * Since this is exported="false" in the manifest, malicious third-party apps cannot send
 * spoofed broadcasts to it. The system Launcher can still execute our PendingIntents 
 * because they run with our application's UID.
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MusicWidgetReceiver.ACTION_PLAY_PAUSE,
            MusicWidgetReceiver.ACTION_LIKE,
            MusicWidgetReceiver.ACTION_NEXT,
            MusicWidgetReceiver.ACTION_PREVIOUS -> {
                val serviceIntent = Intent(context, MusicService::class.java).apply {
                    action = intent.action
                    putExtras(intent)
                }
                startServiceSafely(context, serviceIntent)
            }

            TurntableWidgetReceiver.ACTION_TURNTABLE_PLAY_PAUSE,
            TurntableWidgetReceiver.ACTION_TURNTABLE_NEXT,
            TurntableWidgetReceiver.ACTION_TURNTABLE_PREVIOUS -> {
                val serviceIntent = Intent(context, MusicService::class.java).apply {
                    action = when (intent.action) {
                        TurntableWidgetReceiver.ACTION_TURNTABLE_PLAY_PAUSE -> MusicWidgetReceiver.ACTION_PLAY_PAUSE
                        TurntableWidgetReceiver.ACTION_TURNTABLE_NEXT -> MusicWidgetReceiver.ACTION_NEXT
                        TurntableWidgetReceiver.ACTION_TURNTABLE_PREVIOUS -> MusicWidgetReceiver.ACTION_PREVIOUS
                        else -> intent.action
                    }
                    putExtras(intent)
                }
                startServiceSafely(context, serviceIntent)
            }

            PlaylistWidgetReceiver.ACTION_PLAY_TARGET -> {
                PlaylistWidgetReceiver().processCustomAction(context, intent)
            }

            MusicRecognizerWidgetReceiver.ACTION_START_RECOGNITION,
            MusicRecognizerWidgetReceiver.ACTION_UPDATE_WIDGET,
            MusicRecognizerWidgetReceiver.ACTION_RESET_STATE -> {
                MusicRecognizerWidgetReceiver().processCustomAction(context, intent)
            }
        }
    }

    private fun startServiceSafely(context: Context, serviceIntent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Service restricted")
        }
    }
}
