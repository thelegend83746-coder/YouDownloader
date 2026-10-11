package com.youdownloader.app.data.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.youdownloader.app.MainActivity
import com.youdownloader.app.R
import com.youdownloader.app.data.local.AppDatabase
import com.youdownloader.app.data.model.DownloadStatus
import com.youdownloader.app.data.model.VideoEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val videoId = intent.getLongExtra(EXTRA_VIDEO_ID, -1L)

        if (videoId == -1L) return START_NOT_STICKY

        when (action) {
            ACTION_START_DOWNLOAD, ACTION_RESUME_DOWNLOAD -> {
                serviceScope.launch {
                    val dao = AppDatabase.getInstance(applicationContext).videoDao()
                    val video = dao.getVideoById(videoId)
                    if (video != null) {
                        startForeground(NOTIFICATION_ID, buildNotification(video))
                        DownloadEngine.startDownload(
                            context = applicationContext,
                            scope = serviceScope,
                            video = video,
                            onProgress = { updatedVideo ->
                                updateNotification(updatedVideo)
                                if (updatedVideo.downloadStatus == DownloadStatus.COMPLETED ||
                                    updatedVideo.downloadStatus == DownloadStatus.FAILED) {
                                    checkServiceTermination()
                                }
                            }
                        )
                    }
                }
            }
            ACTION_PAUSE_DOWNLOAD -> {
                serviceScope.launch {
                    DownloadEngine.pauseDownload(applicationContext, videoId)
                    checkServiceTermination()
                }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                serviceScope.launch {
                    DownloadEngine.cancelDownload(applicationContext, videoId)
                    checkServiceTermination()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun checkServiceTermination() {
        serviceScope.launch {
            val dao = AppDatabase.getInstance(applicationContext).videoDao()
            // If no videos are currently downloading, stop foreground
            val active = dao.getVideoById(-1L) // dummy check or check engine
            // Wait brief moment or stop if none active
            stopForeground(STOP_FOREGROUND_DETACH)
        }
    }

    private fun buildNotification(video: VideoEntity): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Pause / Resume action
        val isPaused = video.downloadStatus == DownloadStatus.PAUSED
        val toggleAction = if (isPaused) ACTION_RESUME_DOWNLOAD else ACTION_PAUSE_DOWNLOAD
        val toggleTitle = if (isPaused) "Resume" else "Pause"

        val toggleIntent = Intent(this, DownloadService::class.java).apply {
            action = toggleAction
            putExtra(EXTRA_VIDEO_ID, video.id)
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            1,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Cancel action
        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_VIDEO_ID, video.id)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            2,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = when (video.downloadStatus) {
            DownloadStatus.DOWNLOADING -> "Downloading: ${video.progressPercent}%"
            DownloadStatus.PAUSED -> "Paused"
            DownloadStatus.COMPLETED -> "Download completed"
            DownloadStatus.FAILED -> "Download failed: ${video.errorMessage ?: ""}"
            else -> "Queued"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(video.title)
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(video.downloadStatus == DownloadStatus.DOWNLOADING)
            .setOnlyAlertOnce(true)

        if (video.downloadStatus == DownloadStatus.DOWNLOADING) {
            builder.setProgress(100, video.progressPercent, false)
            builder.addAction(0, toggleTitle, togglePendingIntent)
            builder.addAction(0, "Cancel", cancelPendingIntent)
        } else if (video.downloadStatus == DownloadStatus.PAUSED) {
            builder.setProgress(100, video.progressPercent, false)
            builder.addAction(0, toggleTitle, togglePendingIntent)
            builder.addAction(0, "Cancel", cancelPendingIntent)
        }

        return builder.build()
    }

    private fun updateNotification(video: VideoEntity) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(video))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YouDownloader:DownloadWakeLock").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L) // 1 hour max
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "you_downloader_downloads_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_DOWNLOAD = "com.youdownloader.action.START_DOWNLOAD"
        const val ACTION_PAUSE_DOWNLOAD = "com.youdownloader.action.PAUSE_DOWNLOAD"
        const val ACTION_RESUME_DOWNLOAD = "com.youdownloader.action.RESUME_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.youdownloader.action.CANCEL_DOWNLOAD"

        const val EXTRA_VIDEO_ID = "extra_video_id"

        fun startDownload(context: Context, videoId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_VIDEO_ID, videoId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pauseDownload(context: Context, videoId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE_DOWNLOAD
                putExtra(EXTRA_VIDEO_ID, videoId)
            }
            context.startService(intent)
        }

        fun resumeDownload(context: Context, videoId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_RESUME_DOWNLOAD
                putExtra(EXTRA_VIDEO_ID, videoId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelDownload(context: Context, videoId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_VIDEO_ID, videoId)
            }
            context.startService(intent)
        }
    }
}
