package com.marcorx6.library.data

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.marcorx6.library.R
import com.marcorx6.library.ui.activity.MainActivity

/** Handles notification actions without requiring the app's Activity to be open. */
internal class DownloadNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
        if (id <= 0L) return
        val app = context.applicationContext
        val repository = DownloadRepository(app)
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel(app, manager)
        val activeNotificationId = ResumableDownloadWorker.notificationId(id)
        val pausedId = pausedNotificationId(id)
        when (intent.action) {
            ACTION_PAUSE -> {
                if (repository.pauseDownload(id)) {
                    // WorkManager removes its foreground notification when the worker stops.
                    // Use a separate ID so that teardown cannot also remove the paused notice.
                    manager.notify(pausedId, pausedNotification(app, id))
                }
            }
            ACTION_RESUME -> {
                manager.cancel(pausedId)
                manager.cancel(activeNotificationId)
                repository.resumeDownload(id)
            }
            ACTION_CANCEL -> {
                repository.deleteDownload(id)
                manager.cancel(pausedId)
                manager.cancel(activeNotificationId)
            }
        }
    }

    private fun localized(context: Context, value: String): String {
        val language = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getString("language", "ar") ?: "ar"
        return com.marcorx6.library.ui.LanguageTranslator.translate(value, language)
    }

    private fun ensureNotificationChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, localized(context, "تنزيلات MARCO"), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun pausedNotificationId(id: Long): Int =
        ResumableDownloadWorker.notificationId(id) xor Int.MIN_VALUE

    private fun pausedNotification(context: Context, id: Long) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("MARCO RX6")
            .setContentText(localized(context, "التنزيل متوقف مؤقتاً"))
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context, id))
            .addAction(
                android.R.drawable.ic_media_play,
                localized(context, "استئناف"),
                action(context, id, ACTION_RESUME)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                localized(context, "إلغاء"),
                action(context, id, ACTION_CANCEL)
            )
            .build()

    private fun openApp(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_download_manager", true)
            putExtra("download_id", id)
        }
        return PendingIntent.getActivity(
            context, ResumableDownloadWorker.notificationId(id), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun action(context: Context, id: Long, action: String): PendingIntent {
        val intent = Intent(context, DownloadNotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_DOWNLOAD_ID, id)
        }
        return PendingIntent.getBroadcast(
            context, ResumableDownloadWorker.notificationId(id) xor action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val EXTRA_DOWNLOAD_ID = "download_id"
        const val ACTION_PAUSE = "com.marcorx6.library.DOWNLOAD_PAUSE"
        const val ACTION_RESUME = "com.marcorx6.library.DOWNLOAD_RESUME"
        const val ACTION_CANCEL = "com.marcorx6.library.DOWNLOAD_CANCEL"
        private const val CHANNEL_ID = "marco_rx6_downloads"
    }
}
