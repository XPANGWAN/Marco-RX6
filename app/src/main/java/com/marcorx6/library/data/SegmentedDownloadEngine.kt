package com.marcorx6.library.data

import android.app.DownloadManager
import android.os.StatFs
import android.os.Build
import android.webkit.CookieManager
import android.net.Uri
import android.os.Environment
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import android.system.Os
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Parallel HTTP Range downloader. Falls back to the regular worker when the server
 * does not prove byte-range support or the file is too small.
 *
 * Each range is stored independently so WorkManager retries can resume each range
 * without mixing bytes from different parts of the file.
 */
internal object SegmentedDownloadEngine {
    private const val SEGMENT_COUNT = 4
    private const val MIN_FILE_SIZE = 4L * 1024L * 1024L
    private const val BUFFER_SIZE = 64 * 1024

    fun download(
        context: Context,
        item: StoredDownload,
        database: DownloadDatabase,
        destinationUri: Uri?,
        isStopped: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
        onPreparing: (Int) -> Unit
    ): Boolean {
        // SAF providers are not required to support positional writes. Route those
        // downloads through the resumable sequential writer, which now writes into
        // the selected document itself instead of a hidden private staging copy.
        if (DownloadStoragePreferences(context).treeUri() != null) return false

        val directory = File(context.filesDir, "MARCO_RX6_downloads").apply { mkdirs() }
        val target = File(directory, "${item.id}.part")
        val probe = openConnection(item, 0L, 0L)
        val total: Long
        val etag: String
        val modified: String
        val mime: String
        val safeName: String
        try {
            val code = probe.responseCode
            if (!com.marcorx6.library.network.SecureUrlPolicy.isAllowedDownloadUrl(probe.url.toString())) throw SecurityException("Download redirect target is not trusted")
            val probeMime = probe.contentType?.substringBefore(';')?.trim().orEmpty()
            if (probeMime.equals("text/html", true) || probeMime.equals("application/xhtml+xml", true)) {
                discardSegmentedArtifactsIfPresent(context, directory, target, item, database)
                clearSegments(directory, item.id)
                return false
            }
            val contentRange = DownloadTransferProtocol.parseContentRange(
                probe.getHeaderField("Content-Range").orEmpty()
            )
            val parsedTotal = contentRange?.takeIf {
                it.start == 0L && it.endInclusive == 0L
            }?.total
            if (code != HttpURLConnection.HTTP_PARTIAL || parsedTotal == null || parsedTotal < MIN_FILE_SIZE) {
                discardSegmentedArtifactsIfPresent(context, directory, target, item, database)
                clearSegments(directory, item.id)
                return false
            }
            total = parsedTotal
            etag = probe.getHeaderField("ETag").orEmpty()
            modified = probe.getHeaderField("Last-Modified").orEmpty()
            if (etag.isBlank() && modified.isBlank()) {
                discardSegmentedArtifactsIfPresent(context, directory, target, item, database)
                clearSegments(directory, item.id)
                return false
            }
            mime = probe.contentType?.substringBefore(';')?.trim().orEmpty().ifBlank { item.mimeType }
            val responseName = DownloadFileNames.fromResponse(
                probe.getHeaderField("Content-Disposition"), probe.url.toString()
            )
            safeName = DownloadFileNames.safeName(responseName ?: item.title, mime, item.id)
            // Consume the one-byte probe response so the connection can be closed cleanly.
            probe.inputStream.use { it.read() }
        } finally {
            probe.disconnect()
        }

        val sourceValidatorChanged = when {
            item.etag.isNotBlank() -> etag != item.etag
            item.lastModified.isNotBlank() -> modified != item.lastModified
            else -> false
        }
        val previousVersionCannotBeVerified = item.totalBytes > 0L &&
            item.etag.isBlank() && item.lastModified.isBlank()
        if (sourceValidatorChanged || previousVersionCannotBeVerified ||
            (item.totalBytes > 0L && item.totalBytes != total)
        ) {
            clearSegments(directory, item.id)
            // A changed/unverifiable source invalidates old bytes, not the user's
            // visible destination. Reset that same MediaStore document in place.
            DownloadDestination.resetDirectTarget(context, item.id)
            target.delete()
            database.progress(item.id, 0L, total, etag, modified)
        }

        // Prefer a visible MediaStore file in Download/MARCO RX6 and write all
        // byte ranges into it directly. SAF-selected folders and unsupported providers
        // retain the private-file fallback because they may not support positional writes.
        val ranges = DownloadRangePlanner.create(total, SEGMENT_COUNT, MIN_FILE_SIZE)
            ?: return false
        val hasAllProgressSidecars = (0 until SEGMENT_COUNT).all { index ->
            val range = ranges[index]
            range.start >= total || File(directory, "${item.id}.seg$index.progress").isFile
        }
        // On Android 10+, the default destination must be the visible MediaStore
        // file. Do not silently fall back to a private .part file if creation fails.
        val directUri: Uri? = destinationUri?.takeIf { DownloadDestination.isMediaStoreTarget(it) }

        var existingTargetIsReusable = if (directUri != null) {
            runCatching { DownloadDestination.directTargetSize(context, directUri!!) == total }.getOrDefault(false) &&
                hasAllProgressSidecars
        } else target.isFile && target.length() == total && hasAllProgressSidecars

        if (!existingTargetIsReusable) clearSegments(directory, item.id)
        database.preparing(item.id, 0, total, etag, modified)
        onPreparing(0)
        if (directUri != null) {
            if (!existingTargetIsReusable) {
                try {
                    val available = StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes
                    if (available < total) throw IOException("Not enough free space in Download storage")
                    DownloadDestination.prepareDirectTarget(
                        context, directUri, total, onPreparing,
                        shouldStop = {
                            isStopped() ||
                                database.get(item.id)?.status == DownloadManager.STATUS_PAUSED ||
                                database.get(item.id)?.status == DownloadDatabase.STATUS_CANCELLED
                        }
                    )
                } catch (error: Exception) {
                    if (error is InterruptedException) throw error
                    // Keep the same published MediaStore row and URI on failure.
                    // A retry must reopen this file, never create a second visible item.
                    runCatching { DownloadDestination.truncateDirectTarget(context, directUri, 0L) }
                    throw IOException(
                        "Could not reserve the full visible file in Download/MARCO RX6. The same destination was retained for a safe retry.",
                        error
                    )
                }
            } else onPreparing(100)
        }
        if (directUri == null) {
            if (!existingTargetIsReusable) {
                target.delete()
                val availableBytes = StatFs(directory.absolutePath).availableBytes
                if (availableBytes < total) {
                    throw IOException("Not enough free storage to prepare this file: need $total bytes, available $availableBytes bytes")
                }
                try {
                    RandomAccessFile(target, "rw").use { file ->
                        file.setLength(total)
                        var preparedBytes = 0L
                        for (index in 0 until SEGMENT_COUNT) {
                            val range = ranges[index]
                            val start = range.start
                            if (start >= total) continue
                            val segmentLength = range.length
                            Os.posix_fallocate(file.fd, start, segmentLength)
                            preparedBytes += segmentLength
                            onPreparing((preparedBytes.toDouble() * 100.0 / total.toDouble()).toInt().coerceIn(1, 100))
                        }
                        file.fd.sync()
                    }
                } catch (error: Exception) {
                    target.delete()
                    clearSegments(directory, item.id)
                    throw IOException("Could not reserve storage for this large download. Free space or the storage filesystem may not support preallocation.", error)
                }
            } else onPreparing(100)
        } else {
            target.delete()
        }
        database.finishPreparing(item.id)
        for (index in 0 until SEGMENT_COUNT) {
            val range = ranges[index]
            val start = range.start
            if (start >= total) continue
            val progressFile = File(directory, "${item.id}.seg$index.progress")
            if (!progressFile.isFile) writeSegmentProgress(progressFile, 0L)
        }

        // Keep four stable byte ranges so interrupted segments remain reusable even
        // when the user changes the number of simultaneous HTTP connections.
        val activeConnections = DownloadConcurrencyPreferences(context).segmentConnections().coerceIn(1, SEGMENT_COUNT)
        val executor = Executors.newFixedThreadPool(activeConnections)
        val speedLimiter = DownloadSpeedLimiter(DownloadConcurrencyPreferences(context).speedLimitBytesPerSecond())
        val completedBytes = java.util.concurrent.atomic.AtomicLong(
            (0 until SEGMENT_COUNT).sumOf { index ->
                val range = ranges[index]
                if (range.start >= total) 0L else {
                    val progressFile = File(directory, "${item.id}.seg$index.progress")
                    val limit = range.length
                    readSegmentProgress(progressFile, limit)
                }
            }
        )
        database.progress(item.id, completedBytes.get(), total, etag, modified)
        onProgress(completedBytes.get(), total)
        val lastProgressUpdate = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        try {
            val jobs = (0 until SEGMENT_COUNT).mapNotNull { index ->
                val range = ranges[index]
                val start = range.start
                if (start >= total) return@mapNotNull null
                val end = range.endInclusive
                Callable {
                    val progressFile = File(directory, "${item.id}.seg$index.progress")
                    downloadRange(context, item, target, directUri, progressFile, start, end, total, etag, modified, isStopped, speedLimiter) { delta ->
                        val current = completedBytes.addAndGet(delta).coerceAtMost(total)
                        val now = System.currentTimeMillis()
                        val previous = lastProgressUpdate.get()
                        if (now - previous >= 500L || current >= total) {
                            if (lastProgressUpdate.compareAndSet(previous, now) || current >= total) {
                                database.progress(item.id, current, total, etag, modified)
                                onProgress(current, total)
                            }
                        }
                    }
                }
            }
            val futures = executor.invokeAll(jobs)
            futures.forEach { future -> future.get() }
        } catch (error: Exception) {
            if (error is java.util.concurrent.ExecutionException) {
                val cause = error.cause
                if (cause is Exception) throw cause
            }
            throw error
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }

        if (isStopped() || database.get(item.id)?.status == DownloadManager.STATUS_PAUSED ||
            database.get(item.id)?.status == DownloadDatabase.STATUS_CANCELLED) {
            throw InterruptedException("Segmented download stopped")
        }

        val downloaded = (0 until SEGMENT_COUNT).sumOf { index ->
            val range = ranges[index]
            if (range.start >= total) 0L else {
                val limit = range.length
                readSegmentProgress(File(directory, "${item.id}.seg$index.progress"), limit)
            }
        }
        if (downloaded != total) throw IOException("Segmented download incomplete: $downloaded / $total")
        if (directUri != null) {
            if (DownloadDestination.directTargetSize(context, directUri!!) != total) throw IOException("Visible preallocated destination size mismatch")
        } else if (target.length() != total) throw IOException("Preallocated destination size mismatch")
        // All ranges already occupy their final offsets in this single file: no merge.
        // Storage preparation is complete before range transfer; do not re-enter
        // PREPARING after the network has finished.
        database.renameTitle(item.id, safeName)
        database.progress(item.id, total, total, etag, modified)
        val savedLocation = if (directUri != null) {
            directUri.toString()
        } else {
            DownloadDestination.save(
                context,
                DownloadStoragePreferences(context).treeUri(),
                target,
                safeName,
                mime,
                total
            )
        }
        // Commit SUCCESS before dropping the URI checkpoint. If pause races with
        // finalization, the paused worker must still be able to reopen this exact row.
        if (!database.complete(item.id, savedLocation, mime)) {
            if (directUri == null) DownloadDestination.delete(context, savedLocation)
            throw InterruptedException("Download state changed before completion")
        }
        if (directUri != null) DownloadDestination.forgetDirectTarget(context, item.id)
        else target.delete()
        clearSegments(directory, item.id)
        onProgress(total, total)
        return true
    }

    private fun discardSegmentedArtifactsIfPresent(
        context: Context,
        directory: File,
        target: File,
        item: StoredDownload,
        database: DownloadDatabase
    ) {
        val hasSegmentSidecars = (0 until SEGMENT_COUNT).any { index ->
            File(directory, "${item.id}.seg$index.progress").isFile
        }
        val hasPreallocatedPrivateTarget = target.isFile &&
            item.totalBytes > 0L &&
            target.length() == item.totalBytes &&
            item.downloadedBytes < item.totalBytes
        // A visible MediaStore target is now created by DownloadRepository before
        // this HTTP capability probe. Its sidecar alone does not mean that old
        // segmented data exists: deleting it here would make the file disappear
        // whenever the server declines byte ranges and we switch to sequential mode.
        val hasReusableDownloadData = hasSegmentSidecars ||
            hasPreallocatedPrivateTarget ||
            item.downloadedBytes > 0L
        if (!hasReusableDownloadData) return

        // The segmented layout stores bytes at independent absolute offsets.
        // A sequential fallback cannot safely reinterpret those checkpoints as
        // one contiguous prefix. Never erase valid partial data and restart from
        // zero merely because a redirect host stopped honoring Range.
        throw IOException(
            "The source no longer supports the saved segmented resume state. " +
                "The existing destination and segment checkpoints were preserved; " +
                "refresh the source link or restart this download explicitly."
        )
    }

    private fun downloadRange(
        context: Context,
        item: StoredDownload,
        target: File,
        directUri: Uri?,
        progressFile: File,
        rangeStart: Long,
        rangeEnd: Long,
        total: Long,
        expectedEtag: String,
        expectedModified: String,
        isStopped: () -> Boolean,
        speedLimiter: DownloadSpeedLimiter,
        onBytes: (Long) -> Unit
    ) {
        val segmentLength = rangeEnd - rangeStart + 1L
        var offset = readSegmentProgress(progressFile, segmentLength)
        if (directUri != null) {
            if (DownloadDestination.directTargetSize(context, directUri) != total) throw IOException("Visible preallocated download file is missing or truncated")
        } else if (target.length() != total) throw IOException("Preallocated download file is missing or truncated")
        if (offset == segmentLength) return

        val connection = openConnection(item, rangeStart + offset, rangeEnd)
        try {
            val code = connection.responseCode
            if (!com.marcorx6.library.network.SecureUrlPolicy.isAllowedDownloadUrl(connection.url.toString())) throw SecurityException("Download redirect target is not trusted")
            if (code == 408 || code == 429 || code in 500..599) throw IOException("Temporary server error: HTTP $code")
            if (code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("Server stopped supporting byte ranges (HTTP $code)")
            }
            val responseMime = connection.contentType?.substringBefore(';')?.trim().orEmpty()
            if (responseMime.equals("text/html", true) ||
                responseMime.equals("application/xhtml+xml", true)
            ) {
                throw IOException("Server returned a web page instead of a file segment")
            }
            val returnedRange = DownloadTransferProtocol.parseContentRange(
                connection.getHeaderField("Content-Range").orEmpty()
            )
            val responseEtag = connection.getHeaderField("ETag").orEmpty()
            val responseModified = connection.getHeaderField("Last-Modified").orEmpty()
            val responseLength = connection.getHeaderFieldLong("Content-Length", -1L)
            val expectedStart = rangeStart + offset
            if (returnedRange == null || returnedRange.endInclusive != rangeEnd ||
                returnedRange.total != total ||
                !DownloadTransferProtocol.canAppend(
                    requestedOffset = expectedStart,
                    responseStatus = code,
                    range = returnedRange,
                    contentLength = responseLength,
                    expectedEtag = expectedEtag,
                    responseEtag = responseEtag,
                    expectedLastModified = expectedModified,
                    responseLastModified = responseModified
                )
            ) {
                throw IOException("Server returned an invalid or unverifiable byte range")
            }
            val expectedBytes = segmentLength - offset
            val segmentResumeOffset = offset
            val integrityVerifier = DownloadIntegrityVerifier.from(connection)
            var receivedBytes = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var lastSaved = offset
                fun receive(
                    output: (ByteArray, Int) -> Unit,
                    checkpoint: () -> Unit
                ) {
                    while (true) {
                        if (isStopped() || Thread.currentThread().isInterrupted) {
                            throw InterruptedException("Segment download stopped")
                        }
                        val remaining = expectedBytes - receivedBytes
                        if (remaining == 0L) {
                            if (input.read() != -1) throw IOException("Server sent data beyond the requested byte range")
                            break
                        }
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) break
                        integrityVerifier?.update(buffer, count)
                        speedLimiter.acquire(count, isStopped)
                        output(buffer, count)
                        offset += count
                        receivedBytes += count
                        onBytes(count.toLong())
                        if (offset - lastSaved >= 1024L * 1024L) {
                            lastSaved = offset
                            checkpoint()
                        }
                    }
                }
                if (directUri != null) {
                    DownloadDestination.openDirectWriter(context, directUri).use { output ->
                        receive({ bytes, count -> output.writeAt(bytes, count, rangeStart + offset) }) {
                            output.sync()
                            writeSegmentProgress(progressFile, offset)
                        }
                        output.sync()
                        writeSegmentProgress(progressFile, offset)
                    }
                } else {
                    RandomAccessFile(target, "rw").use { output ->
                        output.seek(rangeStart + offset)
                        receive({ bytes, count -> output.write(bytes, 0, count) }) {
                            output.fd.sync()
                            writeSegmentProgress(progressFile, offset)
                        }
                        output.fd.sync()
                        writeSegmentProgress(progressFile, offset)
                    }
                }
            }
            if (receivedBytes != expectedBytes) {
                throw IOException("Segment response was truncated: $receivedBytes / $expectedBytes bytes")
            }
            if (integrityVerifier != null) {
                try {
                    integrityVerifier.verify()
                } catch (error: IOException) {
                    runCatching { writeSegmentProgress(progressFile, segmentResumeOffset) }
                        .onFailure { progressFile.delete() }
                    throw error
                }
            }
            if (readSegmentProgress(progressFile, segmentLength) != segmentLength) {
                throw IOException("Segment length mismatch for bytes $rangeStart-$rangeEnd")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(item: StoredDownload, start: Long, end: Long): HttpURLConnection =
        com.marcorx6.library.network.RedirectingHttpConnection.open(
            item.url,
            onResponse = { response, responseUrl ->
                response.getHeaderFields().entries
                    .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                    .flatMap { it.value.orEmpty() }
                    .forEach { CookieManager.getInstance().setCookie(responseUrl.toString(), it) }
            }
        ) { request, currentUrl, isInitial ->
            request.requestMethod = "GET"
            request.setRequestProperty("Accept", "*/*")
            request.setRequestProperty("Accept-Encoding", "identity")
            request.setRequestProperty("Range", "bytes=$start-$end")
            if (item.userAgent.isNotBlank()) request.setRequestProperty("User-Agent", item.userAgent)
            val cookies = if (isInitial && item.cookie.isNotBlank()) item.cookie
                else CookieManager.getInstance().getCookie(currentUrl.toString()).orEmpty()
            if (cookies.isNotBlank()) request.setRequestProperty("Cookie", cookies)
            if (isInitial && item.referrer.isNotBlank()) request.setRequestProperty("Referer", item.referrer)
        }

    private fun readSegmentProgress(file: File, maximum: Long): Long =
        if (!file.isFile) 0L else runCatching {
            val saved = file.readText().trim().toLongOrNull()
            if (saved != null && saved in 0L..maximum) saved else 0L
        }.getOrDefault(0L)

    private fun writeSegmentProgress(file: File, bytes: Long) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(bytes.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("Could not commit a segment resume checkpoint")
        }
    }

    private fun clearSegments(directory: File, id: Long) {
        (0 until SEGMENT_COUNT).forEach { index ->
            // Remove legacy per-segment data files and current/temporary checkpoints.
            File(directory, "$id.seg$index").delete()
            File(directory, "$id.seg$index.progress").delete()
            File(directory, "$id.seg$index.progress.tmp").delete()
        }
    }
}
