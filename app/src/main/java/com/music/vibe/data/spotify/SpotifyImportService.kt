package com.music.vibe.data.spotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.music.vibe.MainActivity
import com.music.vibe.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground `dataSync` service tied to the lifetime of an active Spotify
 * playlist/album import in [SpotifyImportManager].
 *
 * Ensures the progress notification is removed automatically if the process dies
 * (preventing zombie notifications), throttles progress updates to ~1 Hz, and
 * handles Android 15+ `dataSync` timeouts cleanly via [onTimeout].
 */
class SpotifyImportService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var observerJob: Job? = null
    private var lastNotificationUpdateMs: Long = 0L
    private var lastPhaseClass: Class<*>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground(buildNotification(SpotifyImportManager.state.value))

        when (intent?.action) {
            ACTION_CANCEL -> {
                SpotifyImportManager.cancelImport(this)
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_SAVE_PARTIAL -> {
                SpotifyImportManager.requestSavePartial(this)
                return START_NOT_STICKY
            }
        }

        if (observerJob?.isActive != true) {
            observerJob = scope.launch {
                SpotifyImportManager.state.collect { state ->
                    handleStateUpdate(state)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStateUpdate(state: SpotifyImportState) {
        val now = SystemClock.elapsedRealtime()
        val isPhaseChange = lastPhaseClass != state::class.java
        lastPhaseClass = state::class.java

        when (state) {
            is SpotifyImportState.FetchingMetadata,
            is SpotifyImportState.Saving,
            -> {
                lastNotificationUpdateMs = now
                notifyIfPermitted(NOTIFICATION_ID, buildNotification(state))
            }

            is SpotifyImportState.Matching -> {
                if (isPhaseChange || state.completed == state.total || now - lastNotificationUpdateMs >= THROTTLE_INTERVAL_MS) {
                    lastNotificationUpdateMs = now
                    notifyIfPermitted(NOTIFICATION_ID, buildNotification(state))
                }
            }

            is SpotifyImportState.Completed -> {
                notifyIfPermitted(SUMMARY_NOTIFICATION_ID, buildSummaryNotification(state.title, state.statusSummary))
                shutdown()
            }

            is SpotifyImportState.Interrupted -> {
                val msg = state.lastError ?: "Matched ${state.matchedSongs.size} of ${state.total} songs"
                notifyIfPermitted(SUMMARY_NOTIFICATION_ID, buildSummaryNotification(state.title, msg))
                shutdown()
            }

            is SpotifyImportState.Failed -> {
                notifyIfPermitted(SUMMARY_NOTIFICATION_ID, buildSummaryNotification(getString(R.string.spotify_import_title), state.message))
                shutdown()
            }

            SpotifyImportState.Idle -> {
                shutdown()
            }
        }
    }

    private fun promoteToForeground(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun notifyIfPermitted(id: Int, notification: Notification) {
        if (!SpotifyImportManager.hasNotificationPermission(this)) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(id, notification) }
    }

    private fun buildNotification(state: SpotifyImportState): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SpotifyImportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val savePartialIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, SpotifyImportService::class.java).setAction(ACTION_SAVE_PARTIAL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent)

        when (state) {
            is SpotifyImportState.FetchingMetadata -> {
                builder
                    .setContentTitle(getString(R.string.spotify_import_title))
                    .setContentText(getString(R.string.spotify_import_fetching))
                    .setProgress(0, 0, true)
                    .addAction(0, getString(R.string.cancel), cancelIntent)
            }

            is SpotifyImportState.Matching -> {
                builder
                    .setContentTitle(state.title.ifBlank { getString(R.string.spotify_import_title) })
                    .setContentText(getString(R.string.spotify_import_matching, state.completed, state.total))
                    .setProgress(state.total.coerceAtLeast(1), state.completed.coerceIn(0, state.total), false)
                if (state.matchedCount > 0) {
                    builder.addAction(0, getString(R.string.spotify_import_save_partial), savePartialIntent)
                }
                builder.addAction(0, getString(R.string.cancel), cancelIntent)
            }

            is SpotifyImportState.Saving -> {
                builder
                    .setContentTitle(state.title.ifBlank { getString(R.string.spotify_import_title) })
                    .setContentText(getString(R.string.spotify_import_saving, state.matchedCount, state.totalCount))
                    .setProgress(0, 0, true)
            }

            else -> {
                builder
                    .setContentTitle(getString(R.string.spotify_import_title))
                    .setContentText(getString(R.string.spotify_import_fetching))
                    .setProgress(0, 0, true)
            }
        }
        return builder.build()
    }

    private fun buildSummaryNotification(title: String, text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setOngoing(false)
            .setSilent(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.spotify_import_title),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun shutdown() {
        observerJob?.cancel()
        observerJob = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        SpotifyImportManager.onServiceTimeout(this)
        observerJob?.cancel()
        observerJob = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf(startId)
    }

    override fun onTimeout(startId: Int) {
        SpotifyImportManager.onServiceTimeout(this)
        observerJob?.cancel()
        observerJob = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf(startId)
    }

    override fun onDestroy() {
        observerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "spotify_import"
        private const val NOTIFICATION_ID = 2002
        private const val SUMMARY_NOTIFICATION_ID = 2003
        internal const val THROTTLE_INTERVAL_MS = 1000L

        const val ACTION_CANCEL = "com.music.vibe.spotify.CANCEL_IMPORT"
        const val ACTION_SAVE_PARTIAL = "com.music.vibe.spotify.SAVE_PARTIAL"

        fun start(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, SpotifyImportService::class.java)
            runCatching {
                ContextCompat.startForegroundService(appContext, intent)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                appContext.stopService(Intent(appContext, SpotifyImportService::class.java))
            }
        }
    }
}
