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
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.marcorx6.library.R
import com.marcorx6.library.ui.activity.MainActivity
import com.marcorx6.library.ui.LanguageTranslator
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class ResumableDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        if (id <= 0L) return Result.failure()
        val database = DownloadDatabase.get(applicationContext)
        val item = database.get(id) ?: run {
            DownloadWorkScheduler.schedule(applicationContext)
            return Result.failure()
        }
        if (item.status == DownloadManager.STATUS_PAUSED) {
            DownloadWorkScheduler.schedule(applicationContext)
            return Result.success()
        }
        return try {
            if (!database.markRunning(id)) {
                Result.success()
            } else {
                setForeground(createForegroundInfo(item.title, id))
                download(item, database)
            }
        } catch (error: Exception) {
            val latest = database.get(id)
            if (latest == null || latest.status == DownloadDatabase.STATUS_CANCELLED) {
                val directory = File(applicationContext.filesDir, "MARCO_RX6_downloads")
                File(directory, "$id.part").delete()
                (0 until 4).forEach {
                    File(directory, "$id.seg$it").delete()
                    File(directory, "$id.seg$it.progress").delete()
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

    private fun download(item: StoredDownload, database: DownloadDatabase): Result {
        val directory = File(applicationContext.filesDir, "MARCO_RX6_downloads").apply { mkdirs() }
        val partial = File(directory, "${item.id}.part")
        val segmented = SegmentedDownloadEngine.download(
            applicationContext,
            item,
            database,
            isStopped = { isStopped || database.get(item.id)?.status == DownloadManager.STATUS_PAUSED ||
                database.get(item.id)?.status == DownloadDatabase.STATUS_CANCELLED },
            onProgress = { downloaded, total ->
                updateProgressNotification(item.title, item.id, downloaded, total)
            },
            onPreparing = { percent -> updatePreparingNotification(item.title, item.id, percent) }
        )
        if (segmented) return Result.success()
        var offset = if (partial.exists()) partial.length() else 0L
        val speedLimiter = DownloadSpeedLimiter(DownloadConcurrencyPreferences(applicationContext).speedLimitBytesPerSecond())
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(item.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20000
                readTimeout = 30000
                requestMethod = "GET"
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                if (item.userAgent.isNotBlank()) setRequestProperty("User-Agent", item.userAgent)
                if (item.cookie.isNotBlank()) setRequestProperty("Cookie", item.cookie)
                if (item.referrer.isNotBlank()) setRequestProperty("Referer", item.referrer)
                if (offset > 0L) {
                    setRequestProperty("Range", "bytes=$offset-")
                    if (item.etag.isNotBlank()) setRequestProperty("If-Range", item.etag)
                    else if (item.lastModified.isNotBlank()) setRequestProperty("If-Range", item.lastModified)
                }
            }
            val code = connection.responseCode
            if (!connection.url.protocol.equals("https", true)) {
                throw SecurityException("Download redirect did not use HTTPS")
            }
            if (code == 416 && offset > 0L) {
                // Keep partial bytes intact; the source may need refreshing before resume.
                throw IOException("The server rejected resume (HTTP 416). Refresh the game download link to continue.")
            }
            if (code == 408 || code == 429 || code in 500..599) throw IOException("Temporary server error: HTTP $code")
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val append = offset > 0L && code == HttpURLConnection.HTTP_PARTIAL
            val responseEtag = connection.getHeaderField("ETag").orEmpty()
            if (append && item.etag.isNotBlank() && responseEtag.isNotBlank() && item.etag != responseEtag) {
                // A changed validator proves this is a different entity. Discard only now,
                // after proving the old partial cannot safely be appended to the new file.
                partial.delete()
                database.progress(item.id, 0L, -1L, "", "")
                throw IOException("The file version changed; restarting safely from byte zero")
            }
            if (offset > 0L && !append) {
                // The server ignored Range or the entity changed. Restart safely instead of
                // appending a full response to an old partial file.
                partial.delete()
                offset = 0L
            }
            val responseLength = connection.getHeaderFieldLong("Content-Length", -1L)
            val rangeTotal = Regex("""bytes \d+-\d+/(\d+)""")
                .find(connection.getHeaderField("Content-Range").orEmpty())
                ?.groupValues?.getOrNull(1)?.toLongOrNull()
            val total = when {
                append && responseLength >= 0L -> offset + responseLength
                responseLength >= 0L -> responseLength
                rangeTotal != null -> rangeTotal
                else -> -1L
            }
            val etag = connection.getHeaderField("ETag") ?: item.etag
            val modified = connection.getHeaderField("Last-Modified") ?: item.lastModified
            val mime = connection.contentType?.substringBefore(';')?.trim().orEmpty().ifBlank { item.mimeType }
            val responseName = DownloadFileNames.fromResponse(
                connection.getHeaderField("Content-Disposition"),
                connection.url.toString()
            )
            val safeName = DownloadFileNames.safeName(responseName ?: item.title, mime, item.id)
            database.renameTitle(item.id, safeName)
            if (append) {
                val rangeStart = Regex("^bytes (\\d+)-").find(connection.getHeaderField("Content-Range").orEmpty())
                    ?.groupValues?.getOrNull(1)?.toLongOrNull()
                if (rangeStart != offset) {
                    partial.delete()
                    throw IOException("Server returned an invalid resume range")
                }
            }
            database.progress(item.id, offset, total, etag, modified)
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var lastUpdate = System.currentTimeMillis()
                    var lastNotificationUpdate = 0L
                    while (true) {
                        if (isStopped) throw InterruptedException("Download paused or cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        speedLimiter.acquire(count)
                        sink.write(buffer, 0, count)
                        offset += count
                        val now = System.currentTimeMillis()
                        // Persist progress once per second rather than forcing a disk sync
                        // every 500 ms; this reduces flash I/O and wakeups during long downloads.
                        if (now - lastUpdate >= 1_000L) {
                            sink.flush()
                            sink.fd.sync()
                            database.progress(item.id, offset, total, etag, modified)
                            lastUpdate = now
                            if (now - lastNotificationUpdate >= 1_500L) {
                                updateProgressNotification(item.title, item.id, offset, total)
                                lastNotificationUpdate = now
                            }
                        }
                    }
                    sink.fd.sync()
                }
            }
            if (isStopped || database.get(item.id)?.status == DownloadDatabase.STATUS_CANCELLED ||
                database.get(item.id)?.status == DownloadManager.STATUS_PAUSED) {
                throw InterruptedException("Download stopped")
            }
            database.progress(item.id, offset, total, etag, modified)
            if (total > 0L && offset != total) {
                throw IOException("Incomplete file: $offset / $total")
            }
            // Network transfer is finished. Keep the item active while assembling/saving.
            updatePreparingNotification(item.title, item.id)
            val savedLocation = DownloadDestination.save(
                applicationContext,
                DownloadStoragePreferences(applicationContext).treeUri(),
                partial,
                safeName,
                mime,
                total
            )
            partial.delete()
            if (!database.complete(item.id, savedLocation, mime)) {
                deleteSavedLocation(savedLocation)
                throw InterruptedException("Download state changed before completion")
            }
            return Result.success()
        } finally {
            connection?.disconnect()
        }
    }

    private fun deleteSavedLocation(location: String) {
        DownloadDestination.delete(applicationContext, location)
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
        private const val MAX_AUTOMATIC_RETRIES = 5
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
