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
import java.io.RandomAccessFile
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
            onPreparing = { percent ->
                val latest = database.get(item.id)
                database.preparing(
                    item.id, percent,
                    latest?.totalBytes ?: item.totalBytes,
                    latest?.etag ?: item.etag,
                    latest?.lastModified ?: item.lastModified
                )
                updatePreparingNotification(item.title, item.id, percent)
            }
        )
        if (segmented) return Result.success()
        val durableProgress = File(directory, "${item.id}.part.progress")
        val directUri = if (DownloadStoragePreferences(applicationContext).treeUri() == null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) DownloadDestination.openDirectTarget(applicationContext, item.id, item.title, item.mimeType) else null
        var directSize = directUri?.let {
            runCatching { DownloadDestination.directTargetSize(applicationContext, it) }.getOrDefault(0L)
        } ?: 0L
        var offset = if (directUri != null) {
            readDurableOffset(durableProgress, item.downloadedBytes, directSize)
        } else if (partial.exists()) {
            readDurableOffset(durableProgress, item.downloadedBytes, partial.length())
        } else 0L
        if (directUri != null && offset == 0L && directSize > 0L) {
            DownloadDestination.truncateDirectTarget(applicationContext, directUri, 0L)
            directSize = 0L
        }
        // Discard bytes written after the last fsynced checkpoint. They will be
        // downloaded again rather than trusting data that was never committed.
        if (directUri == null && partial.exists() && partial.length() != offset) {
            RandomAccessFile(partial, "rw").use { it.setLength(offset); it.fd.sync() }
        }
        val speedLimiter = DownloadSpeedLimiter(DownloadConcurrencyPreferences(applicationContext).speedLimitBytesPerSecond())
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(item.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30000
                readTimeout = 120000
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
            val responseModified = connection.getHeaderField("Last-Modified").orEmpty()
            val responseLength = connection.getHeaderFieldLong("Content-Length", -1L)
            val contentRange = Regex("""bytes (\d+)-(\d+)/(\d+)""")
                .find(connection.getHeaderField("Content-Range").orEmpty())
            val rangeStart = contentRange?.groupValues?.getOrNull(1)?.toLongOrNull()
            val rangeEnd = contentRange?.groupValues?.getOrNull(2)?.toLongOrNull()
            val rangeTotal = contentRange?.groupValues?.getOrNull(3)?.toLongOrNull()

            if (append) {
                val validatorMatches = when {
                    item.etag.isNotBlank() -> responseEtag == item.etag
                    item.lastModified.isNotBlank() -> responseModified == item.lastModified
                    else -> false
                }
                val rangeMatches = rangeStart == offset &&
                    rangeEnd != null && rangeTotal != null &&
                    rangeEnd >= offset && rangeEnd < rangeTotal &&
                    (responseLength < 0L || responseLength == rangeEnd - offset + 1L)
                if (!validatorMatches || !rangeMatches) {
                    partial.delete()
                    durableProgress.delete()
                    database.progress(item.id, 0L, -1L, "", "")
                    throw IOException("The server could not verify the previous file version and resume range; restarting safely is required")
                }
            }
            if (offset > 0L && !append) {
                // The server ignored Range. Never append a full response to old bytes.
                partial.delete()
                durableProgress.delete()
                if (directUri != null) {
                    DownloadDestination.truncateDirectTarget(applicationContext, directUri, 0L)
                    directSize = 0L
                }
                offset = 0L
            }
            val total = when {
                append && rangeTotal != null -> rangeTotal
                responseLength >= 0L -> responseLength
                else -> -1L
            }
            val responseStartOffset = offset
            if (directUri != null && total > 0L && (directSize != total || (responseStartOffset == 0L && item.downloadedBytes > 0L))) {
                database.preparing(item.id, 0, total, etag, modified)
                DownloadDestination.prepareDirectTarget(applicationContext, directUri, total) { percent ->
                    database.preparing(item.id, percent, total, etag, modified)
                    updatePreparingNotification(item.title, item.id, percent)
                }
                database.finishPreparing(item.id)
            }
            if (directUri != null) DownloadDestination.renameDirectTarget(applicationContext, directUri, safeName)
            val integrityVerifier = DownloadIntegrityVerifier.from(connection)
            val etag = connection.getHeaderField("ETag") ?: item.etag
            val modified = connection.getHeaderField("Last-Modified") ?: item.lastModified
            val mime = connection.contentType?.substringBefore(';')?.trim().orEmpty().ifBlank { item.mimeType }
            val responseName = DownloadFileNames.fromResponse(
                connection.getHeaderField("Content-Disposition"),
                connection.url.toString()
            )
            val safeName = DownloadFileNames.safeName(responseName ?: item.title, mime, item.id)
            database.renameTitle(item.id, safeName)
            database.progress(item.id, offset, total, etag, modified)
            var responseBytes = 0L
            connection.inputStream.use { input ->
                val directWriter = directUri?.let { DownloadDestination.openDirectWriter(applicationContext, it) }
                val fileSink = if (directWriter == null) FileOutputStream(partial, append) else null
                try {
                    val buffer = ByteArray(64 * 1024)
                    var lastUpdate = System.currentTimeMillis()
                    var lastNotificationUpdate = 0L
                    while (true) {
                        if (isStopped) throw InterruptedException("Download paused or cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (total > 0L && count.toLong() > total - offset) {
                            throw IOException("The server sent more bytes than the declared file size")
                        }
                        integrityVerifier?.update(buffer, count)
                        speedLimiter.acquire(count)
                        if (directWriter != null) directWriter.writeAt(buffer, count, offset)
                        else fileSink!!.write(buffer, 0, count)
                        offset += count
                        responseBytes += count
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 1_000L) {
                            fileSink?.flush()
                            if (directWriter != null) directWriter.sync() else fileSink!!.fd.sync()
                            writeDurableOffset(durableProgress, offset)
                            database.progress(item.id, offset, total, etag, modified)
                            lastUpdate = now
                            if (now - lastNotificationUpdate >= 1_500L) {
                                updateProgressNotification(item.title, item.id, offset, total)
                                lastNotificationUpdate = now
                            }
                        }
                    }
                    if (directWriter != null) directWriter.sync() else fileSink!!.fd.sync()
                    writeDurableOffset(durableProgress, offset)
                } finally {
                    runCatching { fileSink?.close() }
                    runCatching { directWriter?.close() }
                }
            }
            if (responseLength >= 0L && responseBytes != responseLength) {
                throw IOException("Truncated response body: $responseBytes / $responseLength bytes")
            }
            if (integrityVerifier != null) {
                try {
                    integrityVerifier.verify()
                } catch (error: IOException) {
                    if (directUri != null) {
                        DownloadDestination.truncateDirectTarget(applicationContext, directUri, responseStartOffset)
                    } else {
                        RandomAccessFile(partial, "rw").use { file ->
                            file.setLength(responseStartOffset)
                            file.fd.sync()
                        }
                    }
                    writeDurableOffset(durableProgress, responseStartOffset)
                    database.progress(item.id, responseStartOffset, total, etag, modified)
                    throw error
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
            database.preparing(item.id, 100, total, etag, modified)
            updatePreparingNotification(item.title, item.id)
            val savedLocation = if (directUri != null) {
                DownloadDestination.forgetDirectTarget(applicationContext, item.id)
                directUri.toString()
            } else {
                DownloadDestination.save(
                    applicationContext,
                    DownloadStoragePreferences(applicationContext).treeUri(),
                    partial,
                    safeName,
                    mime,
                    total
                )
            }
            partial.delete()
            durableProgress.delete()
            File(directory, durableProgress.name + ".tmp").delete()
            database.finishPreparing(item.id)
            if (!database.complete(item.id, savedLocation, mime)) {
                deleteSavedLocation(savedLocation)
                throw InterruptedException("Download state changed before completion")
            }
            return Result.success()
        } finally {
            connection?.disconnect()
        }
    }

    private fun readDurableOffset(file: File, fallback: Long, maximum: Long): Long {
        val saved = runCatching { if (file.isFile) file.readText().trim().toLongOrNull() else null }.getOrNull()
        return when {
            saved != null && saved in 0L..maximum -> saved
            fallback in 0L..maximum -> fallback
            else -> 0L
        }
    }

    private fun writeDurableOffset(file: File, offset: Long) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(offset.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("Could not commit the download resume checkpoint")
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
