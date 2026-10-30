package com.music.vibe.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.music.vibe.R
import com.music.vibe.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while the download queue drains or existing downloads
 * are being exported/moved to a custom folder, and reflects progress into a
 * foreground notification.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var drain: Job? = null
    private var exportRunner: Job? = null
    private var notifier: Job? = null

    /** What the notification is currently about. */
    @Volatile
    private var current: Song? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen within a few seconds of the start request whatever the
        // intent turns out to be, the cancel below included — a service started
        // with startForegroundService and never promoted takes the app down
        // with it.
        promote()

        when (intent?.action) {
            ACTION_CANCEL_ALL -> {
                Downloads.active.value.keys.toList().forEach(Downloads::cancel)
                Downloads.cancelExport()
                exportRunner?.cancel()
                exportRunner = null
                shutdown(stopWork = true)
                return START_NOT_STICKY
            }

            ACTION_CANCEL_EXPORT -> {
                Downloads.cancelExport()
                exportRunner?.cancel()
                exportRunner = null
                if (drain == null && !Downloads.busy()) {
                    shutdown(stopWork = false)
                } else {
                    postNotification()
                }
                return START_NOT_STICKY
            }

            ACTION_START_EXPORT -> {
                val moveMode = intent.getBooleanExtra(EXTRA_MOVE_MODE, false)
                if (exportRunner?.isActive != true) {
                    val job = scope.launch {
                        try {
                            Downloads.runExport(this@DownloadService, moveMode)
                        } finally {
                            exportRunner = null
                            if (drain?.isActive != true && !Downloads.busy()) {
                                shutdown(stopWork = false)
                            } else {
                                postNotification()
                            }
                        }
                    }
                    exportRunner = job
                    Downloads.attachExportJob(job)
                }
                ensureNotifier()
                return START_NOT_STICKY
            }
        }

        if (drain == null) {
            drain = scope.launch {
                try {
                    drainQueue()
                } finally {
                    drain = null
                    if (exportRunner?.isActive != true && !Downloads.isExportRunning()) {
                        shutdown(stopWork = false)
                    } else {
                        postNotification()
                    }
                }
            }
        }
        ensureNotifier()
        return START_NOT_STICKY
    }

    private fun ensureNotifier() {
        if (notifier?.isActive == true) return
        notifier = scope.launch { reflectProgress() }
    }

    private suspend fun drainQueue() = coroutineScope {
        repeat(WORKERS) { launch { work() } }
    }

    private suspend fun work() {
        var idleFor = 0L
        while (true) {
            val song = Downloads.takeNext()
            if (song == null) {
                if (idleFor >= IDLE_GRACE_MS && !Downloads.busy()) return
                delay(IDLE_POLL_MS)
                idleFor += IDLE_POLL_MS
                continue
            }
            idleFor = 0L
            current = song
            postNotification()

            val job = scope.launch { Downloads.run(this@DownloadService, song) }
            Downloads.onRunning(song.videoId, job)
            job.join()
            Downloads.onIdle(song.videoId)
        }
    }

    /** Repost as the running track or batch export advances, slowly enough not to thrash the shade. */
    private suspend fun reflectProgress() {
        combine(Downloads.active, Downloads.exportState) { active, export ->
            active to export
        }.collect {
            postNotification()
            delay(PROGRESS_REFRESH_MS)
        }
    }

    private fun shutdown(stopWork: Boolean) {
        if (stopWork) {
            drain?.cancel()
            exportRunner?.cancel()
        }
        notifier?.cancel()
        drain = null
        exportRunner = null
        notifier = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ dataSync foreground service timeout: cancel in-flight work
        // (which triggers fast .vibe-part- cleanup in its finally/catch block) and
        // stop self immediately so the system does not ANR/kill the process.
        Downloads.cancelExport()
        Downloads.active.value.keys.toList().forEach(Downloads::cancel)
        drain?.cancel()
        exportRunner?.cancel()
        notifier?.cancel()
        drain = null
        exportRunner = null
        notifier = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        Downloads.onStopped()
        super.onDestroy()
    }

    // ---- Notification -------------------------------------------------------

    private fun promote() {
        val notification = buildNotification()
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

    private fun postNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val export = Downloads.exportState.value
        val active = Downloads.active.value
        val runningStates = active.values.filterIsInstance<DownloadState.Running>()
        val waiting = active.count { it.value is DownloadState.Queued }

        if (export is ExportState.Running && runningStates.isEmpty() && waiting == 0) {
            val total = export.total
            val completed = export.completed
            val percent = if (total > 0) ((completed.toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0
            val title = if (export.moveMode) {
                if (total > 0) "Moving downloads ($completed of $total)" else "Moving downloads"
            } else {
                if (total > 0) "Exporting downloads ($completed of $total)" else "Exporting downloads"
            }
            val cancelExportIntent = PendingIntent.getService(
                this,
                1,
                Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_EXPORT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(title)
                .setContentText(export.currentTitle)
                .setProgress(100, percent, total == 0)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(0, "Cancel", cancelExportIntent)
                .build()
        }

        val percent = runningStates
            .takeIf { it.isNotEmpty() }
            ?.let { states -> states.sumOf { it.fraction.toDouble() } / states.size }
            ?.times(100)?.toInt()
            ?: 0

        val song = current
        val title = when {
            runningStates.size > 1 -> "Downloading ${runningStates.size} songs"
            else -> song?.title ?: "Downloading"
        }
        val text = when {
            runningStates.size > 1 && waiting > 0 -> "$waiting more queued"
            runningStates.size > 1 -> song?.title.orEmpty()
            song == null -> "Starting"
            waiting > 0 -> "${song.artist} · $waiting more queued"
            else -> song.artist
        }

        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent, runningStates.isEmpty())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Songs being saved to your Music folder"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 0x8175

        const val ACTION_CANCEL_ALL = "com.music.vibe.download.CANCEL_ALL"
        const val ACTION_START_EXPORT = "com.music.vibe.download.START_EXPORT"
        const val ACTION_CANCEL_EXPORT = "com.music.vibe.download.CANCEL_EXPORT"
        const val EXTRA_MOVE_MODE = "extra_move_mode"

        private const val PROGRESS_REFRESH_MS = 250L
        private const val WORKERS = 4
        private const val IDLE_GRACE_MS = 2_000L
        private const val IDLE_POLL_MS = 100L
    }
}
