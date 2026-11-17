package com.marcorx6.library.data

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.marcorx6.library.R
import com.marcorx6.library.ui.activity.MainActivity
import com.marcorx6.library.ui.LanguageTranslator
import java.io.File
import java.io.IOException

internal class ResumableDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        if (id <= 0L) return Result.failure()
        val lease = DownloadExecutionLock.acquire(applicationContext, id) ?: return Result.retry()
        return lease.use { doWorkWithId(id) }
    }

    private suspend fun doWorkWithId(id: Long): Result {
        val database = DownloadDatabase.get(applicationContext)
        val item = database.get(id) ?: run {
            DownloadWorkScheduler.schedule(applicationContext)
            return Result.failure()
        }
        if (item.status == DownloadManager.STATUS_PAUSED) {
            DownloadWorkScheduler.schedule(applicationContext)
            return Result.success()
        }
        // WorkManager cannot express TRANSPORT_WIFI as a constraint. Never start
        // a transfer over mobile data when Wi-Fi Only is selected; leave it pending
        // until MarcoApplication observes Wi-Fi becoming the active network.
        if (!DownloadNetworkPolicy.canStart(
                wifiOnly = DownloadConcurrencyPreferences(applicationContext).wifiOnly(),
                activeNetworkIsWifi = hasWifiConnection()
            )) {
            database.status(id, DownloadManager.STATUS_PENDING)
            return Result.success()
        }
        return try {
            if (!database.markRunning(id)) {
                Result.success()
            } else {
                setForeground(createForegroundInfo(item.title, id))
                // Ensure legacy and newly queued jobs both have a published final
                // MediaStore row before any network capability probe starts.
                val selectedTree = DownloadStoragePreferences(applicationContext).treeUri()
                val destinationUri = if (selectedTree != null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val visibleName = DownloadFileNames.safeName(item.title, item.mimeType, item.id)
                    DownloadDestination.openTarget(
                        applicationContext, id, selectedTree, visibleName, item.mimeType
                    )
                } else null
                // A previous worker may have verified every byte and then been
                // interrupted while committing the final database state. Reuse the
                // exact destination and finish that transaction before contacting the
                // server again; never restart such an item at PREPARING 0%.
                val recoveredCompletion = recoverCompletedDirectTarget(
                    id, item, database, destinationUri
                )
                if (recoveredCompletion) {
                    Result.success()
                } else {
                // Do not reset a resumed transfer to PREPARING 0%.
                // Engines enter PREPARING only while they actually reserve storage.
                val segmentedCompleted = SegmentedDownloadEngine.download(
                    context = applicationContext,
                    item = item,
                    database = database,
                    destinationUri = destinationUri,
                    isStopped = {
                        isStopped ||
                            (DownloadConcurrencyPreferences(applicationContext).wifiOnly() && !hasWifiConnection()) ||
                            database.get(id)?.status == DownloadManager.STATUS_PAUSED ||
                            database.get(id)?.status == DownloadDatabase.STATUS_CANCELLED
                    },
                    onProgress = { downloaded, total ->
                        updateProgressNotification(item.title, id, downloaded, total)
                    },
                    onPreparing = { percent ->
                        val current = database.get(id)
                        database.preparing(
                            id, percent, current?.totalBytes ?: -1L,
                            current?.etag.orEmpty(), current?.lastModified.orEmpty()
                        )
                        updatePreparingNotification(item.title, id, percent)
                    }
                )
                if (segmentedCompleted) Result.success() else {
                    SequentialDownloadEngine.download(
                        context = applicationContext,
                        item = item,
                        database = database,
                        destinationUri = destinationUri,
                        shouldStop = {
                            isStopped ||
                                (DownloadConcurrencyPreferences(applicationContext).wifiOnly() && !hasWifiConnection()) ||
                                database.get(id)?.status == DownloadManager.STATUS_PAUSED ||
                                database.get(id)?.status == DownloadDatabase.STATUS_CANCELLED
                        },
                        onProgress = { downloaded, total ->
                            updateProgressNotification(item.title, id, downloaded, total)
                        },
                        onPreparing = { percent ->
                            updatePreparingNotification(item.title, id, percent)
                        }
                    )
                    Result.success()
                }
                }
            }
        } catch (error: Exception) {
            val latest = database.get(id)
            if (latest == null || latest.status == DownloadDatabase.STATUS_CANCELLED) {
                val directory = File(applicationContext.filesDir, "MARCO_RX6_downloads")
                File(directory, "$id.part").delete()
                File(directory, "$id.part.progress").delete()
                File(directory, "$id.part.progress.tmp").delete()
                DownloadDestination.removeDirectTarget(applicationContext, id)
                (0 until 4).forEach {
                    File(directory, "$id.seg$it").delete()
                    File(directory, "$id.seg$it.progress").delete()
                    File(directory, "$id.seg$it.progress.tmp").delete()
                }
                Result.success()
            } else if (latest.status == DownloadManager.STATUS_PAUSED) {
                Result.success()
            } else if ((isStopped || error is IOException) && runAttemptCount < MAX_AUTOMATIC_RETRIES) {
                database.status(id, DownloadManager.STATUS_PENDING, error.message ?: "Network interrupted")
                Result.retry()
            } else if (error is IOException) {
                database.status(id, DownloadManager.STATUS_FAILED, error.message ?: "Download needs attention")
                Result.failure()
            } else {
                database.status(id, DownloadManager.STATUS_FAILED, error.message ?: "Download failed")
                Result.failure()
            }
        } finally {
            DownloadWorkScheduler.schedule(applicationContext)
        }
    }

    private fun hasWifiConnection(): Boolean {
        val connectivity = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun recoverCompletedDirectTarget(
        id: Long,
        item: StoredDownload,
        database: DownloadDatabase,
        destinationUri: android.net.Uri?
    ): Boolean {
        val total = item.totalBytes
        if (destinationUri == null || total <= 0L || item.downloadedBytes != total) return false
        val actualSize = runCatching {
            DownloadDestination.directTargetSize(applicationContext, destinationUri)
        }.getOrNull() ?: return false
        if (actualSize != total) return false

        // The sequential engine persists total bytes only after response-length and
        // integrity checks; the segmented engine now does so only after all ranges
        // have joined and passed their integrity checks.
        if (!database.complete(id, destinationUri.toString(), item.mimeType)) return false

        DownloadDestination.forgetDirectTarget(applicationContext, id)
        val directory = File(applicationContext.filesDir, "MARCO_RX6_downloads")
        File(directory, "$id.part").delete()
        File(directory, "$id.part.progress").delete()
        File(directory, "$id.part.progress.tmp").delete()
        (0 until 4).forEach { index ->
            File(directory, "$id.seg$index").delete()
            File(directory, "$id.seg$index.progress").delete()
            File(directory, "$id.seg$index.progress.tmp").delete()
        }
        updateProgressNotification(item.title, id, total, total)
        return true
    }

    private fun updatePreparingNotification(title: String, downloadId: Long, percent: Int = 100) {
        val language = applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getString("language", "ar") ?: "ar"
        val localize: (String) -> String = { LanguageTranslator.translate(it, language) }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title.ifBlank { "MARCO" })
            .setContentText("${localize("جارٍ تجهيز الملف")} ${percent.coerceIn(0, 100)}%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setContentIntent(openAppPendingIntent(applicationContext, downloadId))
            .addAction(android.R.drawable.ic_media_pause, localize("إيقاف مؤقت"),
                actionPendingIntent(applicationContext, downloadId, DownloadNotificationActionReceiver.ACTION_PAUSE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, localize("إلغاء"),
                actionPendingIntent(applicationContext, downloadId, DownloadNotificationActionReceiver.ACTION_CANCEL))
            .build()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId(downloadId), notification)
    }

    private fun updateProgressNotification(title: String, downloadId: Long, downloaded: Long, total: Long) {
        val language = applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getString("language", "ar") ?: "ar"
        val localize: (String) -> String = { LanguageTranslator.translate(it, language) }
        val percent = if (total > 0L) (downloaded * 100L / total).coerceIn(0L, 100L).toInt() else 0
        val text = if (total > 0L) "$percent%" else localize("قيد التنزيل")
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title.ifBlank { "MARCO" })
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, total <= 0L)
            .setContentIntent(openAppPendingIntent(applicationContext, downloadId))
            .addAction(
                android.R.drawable.ic_media_pause,
                "إيقاف مؤقت",
                actionPendingIntent(applicationContext, downloadId, DownloadNotificationActionReceiver.ACTION_PAUSE)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "إلغاء",
                actionPendingIntent(applicationContext, downloadId, DownloadNotificationActionReceiver.ACTION_CANCEL)
            )
            .build()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId(downloadId), notification)
    }

    private fun createForegroundInfo(title: String, downloadId: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val language = applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getString("language", "ar") ?: "ar"
        val localize: (String) -> String = { LanguageTranslator.translate(it, language) }
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                localize("تنزيلات MARCO"),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = localize("تتبع تقدم تنزيلات الألعاب")
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title.ifBlank { "MARCO" })
            .setContentText(localize("قيد التنزيل"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(openAppPendingIntent(applicationContext, downloadId))
            .addAction(
                android.R.drawable.ic_media_pause,
                "إيقاف مؤقت",
                actionPendingIntent(applicationContext, downloadId, DownloadNotificationActionReceiver.ACTION_PAUSE)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "إلغاء",
                actionPendingIntent(applicationContext, downloadId, DownloadNotificationActionReceiver.ACTION_CANCEL)
            )
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notificationId(downloadId), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId(downloadId), notification)
        }
    }

    companion object {
        const val KEY_ID = "download_id"
        private const val MAX_AUTOMATIC_RETRIES = 8
        private const val CHANNEL_ID = "marco_rx6_downloads"
        internal fun notificationId(downloadId: Long): Int = 1_000_000_000 + ((downloadId xor (downloadId ushr 32)).toInt() and 0x3fffffff)

        private fun openAppPendingIntent(context: Context, downloadId: Long): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("open_download_manager", true)
                putExtra("download_id", downloadId)
            }
            return PendingIntent.getActivity(
                context, notificationId(downloadId), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun actionPendingIntent(context: Context, downloadId: Long, action: String): PendingIntent {
            val intent = Intent(context, DownloadNotificationActionReceiver::class.java).apply {
                this.action = action
                putExtra(DownloadNotificationActionReceiver.EXTRA_DOWNLOAD_ID, downloadId)
            }
            val requestCode = notificationId(downloadId) xor action.hashCode()
            return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
