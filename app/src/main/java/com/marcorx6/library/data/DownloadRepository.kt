package com.marcorx6.library.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import androidx.core.content.FileProvider
import com.marcorx6.library.network.SecureUrlPolicy
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal data class DownloadEntry(
    val id: Long,
    val title: String,
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val mimeType: String?,
    val sourcePostUrl: String,
    val priority: Int,
    val segmentBytes: List<Long>,
    val preparationPercent: Int,
    val error: String
)

internal sealed class DownloadStartResult {
    data class Enqueued(val title: String) : DownloadStartResult()
    data class AlreadyQueued(val title: String) : DownloadStartResult()
    data class OpenInBrowser(val finalUrl: String) : DownloadStartResult()
    data class Failed(val message: String) : DownloadStartResult()
    data class RefreshSource(val sourcePostUrl: String) : DownloadStartResult()
}

/** Owns the app's persistent, resumable download queue. */
internal class DownloadRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = DownloadDatabase.get(appContext)
    private val concurrencyPreferences = DownloadConcurrencyPreferences(appContext)

    private companion object {
        val probeExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val activeProbes = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    }

    init {
        // A process death can interrupt the pre-queue HTTP probe. Recover those
        // rows as pending so they are never stranded in a permanent Preparing state.
        database.recoverResolving(activeProbes.toSet())
        database.all().filter { it.id !in activeProbes }.forEach { item ->
            File(appContext.filesDir, "MARCO_RX6_downloads/${item.id}.probing").delete()
        }
        // Re-queue reservations left behind if Android killed the process
        // between the SQLite slot claim and WorkManager starting its worker.
        DownloadWorkScheduler.recoverAndSchedule(appContext)
    }

    private data class EnqueueReservation(
        val duplicate: StoredDownload? = null,
        val id: Long = -1L,
        val previous: StoredDownload? = null
    )

    fun enqueue(
        link: String,
        title: String,
        userAgent: String = "",
        contentDisposition: String = "",
        hintedMimeType: String = "",
        referrer: String = "",
        sourcePostUrl: String = "",
        onResult: (DownloadStartResult) -> Unit
    ) {
        probeExecutor.execute {
            var reservedId = -1L
            val result = try {
                val secureLink = SecureUrlPolicy.toHttpsUrl(link)
                    ?.let { com.marcorx6.library.network.DownloadSourceResolver.normalizeKnownProviderUrl(it) }
                    ?: throw SecurityException("رابط التنزيل غير آمن أو غير صالح؛ يجب استخدام HTTPS")

                // Reserve the queue row and publish its destination BEFORE any HTTP
                // probe. The UI can now show Preparing immediately, and the user
                // can see the actual destination while redirects/headers are checked.
                val reservation = synchronized(database) {
                    val duplicate = database.findActiveDuplicate(secureLink, sourcePostUrl, secureLink)
                    if (duplicate != null) {
                        EnqueueReservation(duplicate = duplicate)
                    } else {
                        val previous = database.findFailedBySource(sourcePostUrl, secureLink)
                        val id = previous?.id ?: database.nextId()
                        reservedId = id
                        if (previous == null) {
                            database.insert(
                                id, secureLink, title.ifBlank { "MARCO" }, userAgent, "",
                                referrer, hintedMimeType, sourcePostUrl, secureLink
                            )
                        } else {
                            val directory = File(appContext.filesDir, "MARCO_RX6_downloads")
                            File(directory, "$id.part").delete()
                            File(directory, "$id.part.progress").delete()
                            File(directory, "$id.part.progress.tmp").delete()
                            DownloadDestination.removeDirectTarget(appContext, id)
                            (0 until 4).forEach { index ->
                                File(directory, "$id.seg$index").delete()
                                File(directory, "$id.seg$index.progress").delete()
                                File(directory, "$id.seg$index.progress.tmp").delete()
                            }
                        }
                        database.status(id, DownloadDatabase.STATUS_RESOLVING)
                        File(appContext.filesDir, "MARCO_RX6_downloads/$id.probing").apply {
                            parentFile?.mkdirs()
                            writeText("resolving")
                        }
                        activeProbes.add(id)
                        // Do not create a public MediaStore file until inspection confirms
                        // that this URL is a direct file. Landing pages must open in the
                        // in-app WebView without touching the download destination.
                        EnqueueReservation(id = id, previous = previous)
                    }
                }

                if (reservation.duplicate != null) {
                    ensureVisibleDefaultTarget(
                        reservation.duplicate.id,
                        reservation.duplicate.title,
                        reservation.duplicate.mimeType
                    )
                    DownloadStartResult.AlreadyQueued(reservation.duplicate.title)
                } else {
                    val id = reservation.id
                    val previous = reservation.previous
                    val probe = inspect(secureLink, userAgent, contentDisposition, hintedMimeType, referrer)
                    if (!probe.isDirectFile) {
                        File(appContext.filesDir, "MARCO_RX6_downloads/$id.probing").delete()
                        synchronized(database) {
                            if (previous == null) {
                                database.cancel(id)
                                database.delete(id)
                            } else {
                                database.status(id, DownloadManager.STATUS_FAILED, "Source is a web page, not a direct file")
                            }
                        }
                        DownloadDestination.removeDirectTarget(appContext, id)
                        // A landing-page probe is a GET and may consume one-time tokens or trigger
                        // provider redirects. Let the WebView make the first real page request
                        // using the exact Blogger destination instead of opening the probe's
                        // final redirect target (which can collapse to the provider homepage).
                        DownloadStartResult.OpenInBrowser(secureLink)
                    } else {
                        // Allocate the visible destination only after a direct file endpoint
                        // has been confirmed. Provider landing pages are handled by WebView.
                        ensureVisibleDefaultTarget(id, title.ifBlank { "MARCO" }, probe.mimeType)
                        if (!SecureUrlPolicy.isAllowedDownloadUrl(probe.finalUrl)) {
                            throw SecurityException("رابط التحويل النهائي غير آمن أو يستخدم خادمًا غير موثوق")
                        }
                        val finalTitle = DownloadFileNames.safeName(
                            probe.fileName ?: title.ifBlank { "MARCO" },
                            probe.mimeType,
                            id,
                            probe.finalUrl
                        )
                        val cookie = CookieManager.getInstance().getCookie(probe.finalUrl).orEmpty()
                        File(appContext.filesDir, "MARCO_RX6_downloads/$id.probing").delete()
                        synchronized(database) {
                            val latest = database.get(id)
                                ?: throw InterruptedException("Download was cancelled before the source was ready")
                            if (latest.status == DownloadDatabase.STATUS_CANCELLED) {
                                throw InterruptedException("Download was cancelled before the source was ready")
                            }
                            database.updateSourceDetails(
                                id, secureLink, probe.finalUrl, finalTitle, userAgent, cookie, referrer, probe.mimeType
                            )
                            // Rename the already-published row rather than creating a
                            // second file when the server supplies its final filename.
                            ensureVisibleDefaultTarget(id, finalTitle, probe.mimeType)
                            if (latest.status == DownloadDatabase.STATUS_RESOLVING) {
                                database.status(id, DownloadDatabase.STATUS_INITIALIZING)
                                DownloadWorkScheduler.schedule(appContext)
                            }
                        }
                        DownloadStartResult.Enqueued(finalTitle)
                    }
                }
            } catch (error: Exception) {
                if (reservedId > 0L) {
                    File(appContext.filesDir, "MARCO_RX6_downloads/$reservedId.probing").delete()
                    val latest = database.get(reservedId)
                    if (latest?.status == DownloadDatabase.STATUS_RESOLVING ||
                        latest?.status == DownloadDatabase.STATUS_INITIALIZING) {
                        database.status(
                            reservedId,
                            DownloadManager.STATUS_FAILED,
                            error.message ?: "Could not initialize download"
                        )
                        DownloadDestination.removeDirectTarget(appContext, reservedId)
                    } else if (latest == null || latest.status == DownloadDatabase.STATUS_CANCELLED) {
                        DownloadDestination.removeDirectTarget(appContext, reservedId)
                    }
                }
                val message = error.message ?: "تعذر بدء تنزيل الملف"
                if (sourcePostUrl.isNotBlank() && Regex("HTTP (401|403|404|410)").containsMatchIn(message)) {
                    DownloadStartResult.RefreshSource(sourcePostUrl)
                } else DownloadStartResult.Failed(message)
            } finally {
                if (reservedId > 0L) activeProbes.remove(reservedId)
            }
            onResult(result)
        }
    }

    fun getDownloads(): List<DownloadEntry> = database.all().map { item ->
        DownloadEntry(
            id = item.id,
            title = item.title,
            status = item.status,
            downloadedBytes = item.downloadedBytes,
            totalBytes = item.totalBytes,
            mimeType = item.mimeType,
            sourcePostUrl = item.sourcePostUrl,
            priority = item.priority,
            preparationPercent = item.preparationPercent,
            error = item.error,
            segmentBytes = (0 until 4).map { index ->
                val directory = File(appContext.filesDir, "MARCO_RX6_downloads")
                val progress = File(directory, "${item.id}.seg$index.progress")
                if (progress.isFile) runCatching { progress.readText().trim().toLongOrNull() ?: 0L }.getOrDefault(0L) else {
                    File(directory, "${item.id}.seg$index").let { file ->
                        if (file.isFile) file.length() else 0L
                    }
                }
            }
        )
    }

    fun pauseDownload(id: Long): Boolean {
        val item = database.get(id) ?: return false
        if (item.status == DownloadManager.STATUS_SUCCESSFUL) return false
        // Persist PAUSED before cancelling the worker so its final completion
        // transaction cannot overwrite the user's pause request.
        database.status(id, DownloadManager.STATUS_PAUSED)
        DownloadWorkScheduler.cancel(appContext, id)
        DownloadWorkScheduler.schedule(appContext)
        return true
    }

    fun resumeDownload(id: Long): Boolean {
        val item = database.get(id) ?: return false
        if (item.status != DownloadManager.STATUS_PAUSED &&
            item.status != DownloadManager.STATUS_FAILED
        ) return false
        val stillResolving = File(appContext.filesDir, "MARCO_RX6_downloads/$id.probing").isFile
        database.status(
            id,
            if (stillResolving) DownloadDatabase.STATUS_RESOLVING else DownloadManager.STATUS_PENDING
        )
        if (!stillResolving) DownloadWorkScheduler.schedule(appContext)
        return true
    }

    fun pauseAllDownloads(): Int {
        val ids = database.pauseAllActive()
        ids.forEach { id -> DownloadWorkScheduler.cancel(appContext, id) }
        return ids.size
    }

    fun resumeAllDownloads(): Int {
        val ids = database.pausedOrFailedIds()
        var schedulable = false
        ids.forEach { id ->
            val stillResolving = File(appContext.filesDir, "MARCO_RX6_downloads/$id.probing").isFile
            database.status(
                id,
                if (stillResolving) DownloadDatabase.STATUS_RESOLVING else DownloadManager.STATUS_PENDING
            )
            if (!stillResolving) schedulable = true
        }
        if (schedulable) DownloadWorkScheduler.schedule(appContext)
        return ids.size
    }

    fun setDownloadPriority(id: Long, priority: Int): Boolean {
        if (database.get(id) == null) return false
        database.setPriority(id, priority)
        DownloadWorkScheduler.schedule(appContext)
        return true
    }

    fun retryAllFailed(): Int {
        val failed = database.all().filter { it.status == DownloadManager.STATUS_FAILED }
        failed.forEach { database.status(it.id, DownloadManager.STATUS_PENDING) }
        if (failed.isNotEmpty()) DownloadWorkScheduler.schedule(appContext)
        return failed.size
    }

    fun clearCompletedDownloads(): Int = database.deleteCompleted()

    fun wifiOnlyDownloads(): Boolean = concurrencyPreferences.wifiOnly()

    fun isWaitingForWifi(): Boolean {
        if (!wifiOnlyDownloads()) return false
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return true
        val network = connectivity.activeNetwork ?: return true
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun setWifiOnlyDownloads(enabled: Boolean) {
        concurrencyPreferences.setWifiOnly(enabled)
        DownloadWorkScheduler.rescheduleForNetworkChange(appContext)
    }

    fun maxConcurrentDownloads(): Int = concurrencyPreferences.maxConcurrent()

    fun maxSegmentConnections(): Int = concurrencyPreferences.segmentConnections()

    fun downloadSpeedLimitBytesPerSecond(): Long = concurrencyPreferences.speedLimitBytesPerSecond()

    fun setDownloadSpeedLimitBytesPerSecond(value: Long) {
        concurrencyPreferences.setSpeedLimitBytesPerSecond(value)
    }

    fun setMaxSegmentConnections(value: Int) {
        concurrencyPreferences.setSegmentConnections(value)
    }

    fun setMaxConcurrentDownloads(value: Int) {
        concurrencyPreferences.setMaxConcurrent(value)
        DownloadWorkScheduler.schedule(appContext)
    }

    fun openDownloadedFile(id: Long): Uri? {
        val item = database.get(id) ?: return null
        if (item.status != DownloadManager.STATUS_SUCCESSFUL || item.localPath.isBlank()) return null
        if (item.localPath.startsWith("content://")) return Uri.parse(item.localPath)
        val file = File(item.localPath)
        if (!file.isFile) return null
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.files",
            file
        )
    }

    fun deleteDownload(id: Long): Boolean {
        val item = database.get(id) ?: return false
        database.cancel(id)
        DownloadWorkScheduler.cancel(appContext, id)
        if (item.localPath.startsWith("content://")) {
            DownloadDestination.delete(appContext, item.localPath)
        } else if (item.localPath.isNotBlank()) {
            File(item.localPath).delete()
        }
        val directory = File(appContext.filesDir, "MARCO_RX6_downloads")
        File(directory, "$id.part").delete()
        File(directory, "$id.part.progress").delete()
        File(directory, "$id.part.progress.tmp").delete()
        DownloadDestination.removeDirectTarget(appContext, id)
        (0 until 4).forEach { index ->
            File(directory, "$id.seg$index").delete()
            File(directory, "$id.seg$index.progress").delete()
            File(directory, "$id.seg$index.progress.tmp").delete()
        }
        database.delete(id)
        DownloadWorkScheduler.schedule(appContext)
        return true
    }

    private fun ensureVisibleDefaultTarget(id: Long, title: String, mimeType: String) {
        val selectedTree = DownloadStoragePreferences(appContext).treeUri()
        if (selectedTree == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val visibleName = DownloadFileNames.safeName(title, mimeType, id)
        val target = DownloadDestination.openTarget(appContext, id, selectedTree, visibleName, mimeType)
        // SAF providers may keep the initially published title when the probe later
        // discovers the real extension. Rename the same document in place when supported.
        if (selectedTree != null && target != null) {
            DownloadDestination.renameDirectTarget(appContext, id, target, visibleName)
        }
    }

    private data class DownloadProbe(
        val finalUrl: String,
        val mimeType: String,
        val isDirectFile: Boolean,
        val fileName: String?
    )

    private fun inspect(
        link: String,
        userAgent: String,
        hintedDisposition: String,
        hintedMimeType: String,
        referrer: String,
        resolutionDepth: Int = 0
    ): DownloadProbe {
        if (com.marcorx6.library.network.DownloadSourceResolver.isDirectDownloadEndpointUrl(link)) {
            return DownloadProbe(link, "application/octet-stream", true, null)
        }
        if (com.marcorx6.library.network.DownloadSourceResolver.isKnownLandingPageUrl(link)) {
            return DownloadProbe(link, "text/html", false, null)
        }
        val connection = com.marcorx6.library.network.RedirectingHttpConnection.open(
            link,
            onResponse = { response, responseUrl ->
                response.getHeaderFields().entries
                    .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                    .flatMap { it.value.orEmpty() }
                    .forEach { cookie ->
                        CookieManager.getInstance().setCookie(responseUrl.toString(), cookie)
                    }
            }
        ) { request, currentUrl, isInitial ->
            request.readTimeout = 60000
            request.requestMethod = "GET"
            request.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/octet-stream,*/*")
            request.setRequestProperty("Accept-Encoding", "identity")
            if (userAgent.isNotBlank()) request.setRequestProperty("User-Agent", userAgent)
            if (isInitial && referrer.isNotBlank()) request.setRequestProperty("Referer", referrer)
            CookieManager.getInstance().getCookie(currentUrl.toString())?.takeIf { it.isNotBlank() }?.let {
                request.setRequestProperty("Cookie", it)
            }
        }
        try {
            val code = connection.responseCode
            val mime = (connection.contentType ?: hintedMimeType)
                .substringBefore(';').trim().lowercase(Locale.ROOT)
            val disposition = connection.getHeaderField("Content-Disposition") ?: hintedDisposition
            val finalUrl = connection.url.toString()
            if (code !in 200..299) throw IllegalStateException("الخادم رفض رابط التنزيل (HTTP $code)")
            if (!SecureUrlPolicy.isAllowedDownloadUrl(finalUrl)) {
                throw SecurityException("وجهة التحويل غير آمنة أو غير موثوقة")
            }
            if (mime == "text/html" || mime == "application/xhtml+xml") {
                if (resolutionDepth < 3) {
                    val html = readBoundedHtml(connection, 1_500_000)
                    val candidate = com.marcorx6.library.network.DownloadSourceResolver
                        .extractDownloadUrl(finalUrl, html)
                    if (!candidate.isNullOrBlank() && candidate != finalUrl) {
                        connection.disconnect()
                        return inspect(candidate, userAgent, "", hintedMimeType, finalUrl, resolutionDepth + 1)
                    }
                }
                return DownloadProbe(finalUrl, mime, false, null)
            }
            val path = Uri.parse(finalUrl).lastPathSegment.orEmpty().lowercase(Locale.ROOT)
            val hasFileExtension = Regex("""\.(apk|zip|7z|rar|iso|pkg|bin|cue|chd|img|pbp|cso|nsp|xci|pdf|mp4|m4v|mkv|webm|mov|avi|flv|3gp|mp3|m4a|aac|wav|flac|ogg)$""").containsMatchIn(path)
            val responseFileName = DownloadFileNames.fromResponse(
                connection.getHeaderField("Content-Disposition"),
                finalUrl
            ) ?: DownloadFileNames.fromResponse(hintedDisposition, finalUrl)
            val isAttachment = disposition.contains("attachment", ignoreCase = true) || responseFileName != null
            val structuredText = mime in setOf(
                "application/json", "application/ld+json", "application/xml",
                "application/javascript", "application/x-javascript",
                "application/x-www-form-urlencoded", "application/problem+json"
            ) || mime.startsWith("text/")
            val isBinary = mime == "application/octet-stream" ||
                (mime.startsWith("application/") && !structuredText) ||
                mime.startsWith("video/") || mime.startsWith("audio/")
            return DownloadProbe(finalUrl, mime, isAttachment || isBinary || hasFileExtension, responseFileName)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBoundedHtml(connection: HttpURLConnection, maximumBytes: Int): String {
        val stream = runCatching { connection.inputStream }.getOrElse {
            connection.errorStream ?: return ""
        }
        return stream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var remaining = maximumBytes
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
            output.toString(Charsets.UTF_8.name())
        }
    }
}
