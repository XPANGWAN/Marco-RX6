package com.marcorx6.library.data

import android.app.DownloadManager
import android.os.StatFs
import android.content.Context
import java.io.File
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
        isStopped: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
        onPreparing: (Int) -> Unit
    ): Boolean {
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
            if (!probe.url.protocol.equals("https", true)) throw SecurityException("Download redirect did not use HTTPS")
            val contentRange = probe.getHeaderField("Content-Range").orEmpty()
            val parsedTotal = Regex("""bytes 0-0/(\d+)""").find(contentRange)
                ?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (code != HttpURLConnection.HTTP_PARTIAL || parsedTotal == null || parsedTotal < MIN_FILE_SIZE) {
                clearSegments(directory, item.id)
                return false
            }
            total = parsedTotal
            etag = probe.getHeaderField("ETag").orEmpty()
            modified = probe.getHeaderField("Last-Modified").orEmpty()
            if (etag.isBlank() && modified.isBlank()) {
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

        if ((item.etag.isNotBlank() && etag.isNotBlank() && item.etag != etag) ||
            (item.totalBytes > 0L && item.totalBytes != total) ||
            (item.lastModified.isNotBlank() && modified.isNotBlank() && item.lastModified != modified)
        ) {
            clearSegments(directory, item.id)
            target.delete()
            database.progress(item.id, 0L, total, etag, modified)
        }

        // Keep one preallocated destination file for all byte ranges. Each worker writes
        // directly at its absolute offset; this removes the old four-file merge and its
        // extra full-size temporary copy (important for 100-300 GB downloads).
        val segmentSize = ((total - 1L) / SEGMENT_COUNT) + 1L
        val existingTargetIsReusable = target.isFile && target.length() == total
        if (!existingTargetIsReusable) {
            target.delete()
            clearSegments(directory, item.id)
        }
        val availableBytes = StatFs(directory.absolutePath).availableBytes
        if (availableBytes < total) {
            throw IOException("Not enough free storage to prepare this file: need $total bytes, available $availableBytes bytes")
        }
        onPreparing(0)
        try {
            RandomAccessFile(target, "rw").use { file ->
                file.setLength(total)
                Os.posix_fallocate(file.fd, 0L, total)
                file.fd.sync()
            }
        } catch (error: Exception) {
            target.delete()
            clearSegments(directory, item.id)
            throw IOException("Could not reserve storage for this large download. Free space or the storage filesystem may not support preallocation.", error)
        }
        var preparedBytes = 0L
        for (index in 0 until SEGMENT_COUNT) {
            val start = index * segmentSize
            if (start >= total) continue
            val segmentLength = minOf(total - start, segmentSize)
            val progressFile = File(directory, "${item.id}.seg$index.progress")
            if (!progressFile.isFile) writeSegmentProgress(progressFile, 0L)
            preparedBytes += segmentLength
            onPreparing((preparedBytes.toDouble() * 100.0 / total.toDouble()).toInt().coerceIn(1, 100))
        }

        // Keep four stable byte ranges so interrupted segments remain reusable even
        // when the user changes the number of simultaneous HTTP connections.
        val activeConnections = DownloadConcurrencyPreferences(context).segmentConnections().coerceIn(1, SEGMENT_COUNT)
        val executor = Executors.newFixedThreadPool(activeConnections)
        val speedLimiter = DownloadSpeedLimiter(DownloadConcurrencyPreferences(context).speedLimitBytesPerSecond())
        val completedBytes = java.util.concurrent.atomic.AtomicLong(
            (0 until SEGMENT_COUNT).sumOf { index ->
                val start = index * segmentSize
                if (start >= total) 0L else {
                    val progressFile = File(directory, "${item.id}.seg$index.progress")
                    val limit = minOf(total - start, segmentSize)
                    readSegmentProgress(progressFile, limit)
                }
            }
        )
        database.progress(item.id, completedBytes.get(), total, etag, modified)
        onProgress(completedBytes.get(), total)
        val lastProgressUpdate = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        try {
            val jobs = (0 until SEGMENT_COUNT).mapNotNull { index ->
                val start = index * segmentSize
                if (start >= total) return@mapNotNull null
                val end = minOf(total - 1L, start + segmentSize - 1L)
                Callable {
                    val progressFile = File(directory, "${item.id}.seg$index.progress")
                    downloadRange(item, target, progressFile, start, end, total, etag, modified, isStopped, speedLimiter) { delta ->
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
            val start = index * segmentSize
            if (start >= total) 0L else {
                val limit = minOf(total - start, segmentSize)
                readSegmentProgress(File(directory, "${item.id}.seg$index.progress"), limit)
            }
        }
        if (downloaded != total) throw IOException("Segmented download incomplete: $downloaded / $total")
        if (target.length() != total) throw IOException("Preallocated destination size mismatch")
        // All ranges already occupy their final offsets in this single file: no merge.
        onPreparing(100)

        database.renameTitle(item.id, safeName)
        database.progress(item.id, total, total, etag, modified)
        val savedLocation = DownloadDestination.save(
            context,
            DownloadStoragePreferences(context).treeUri(),
            target,
            safeName,
            mime,
            total
        )
        target.delete()
        clearSegments(directory, item.id)
        if (!database.complete(item.id, savedLocation, mime)) {
            DownloadDestination.delete(context, savedLocation)
            throw InterruptedException("Download state changed before completion")
        }
        onProgress(total, total)
        return true
    }

    private fun downloadRange(
        item: StoredDownload,
        target: File,
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
        if (target.length() != total) throw IOException("Preallocated download file is missing or truncated")
        if (offset == segmentLength) return

        val connection = openConnection(item, rangeStart + offset, rangeEnd)
        try {
            val code = connection.responseCode
            if (!connection.url.protocol.equals("https", true)) throw SecurityException("Download redirect did not use HTTPS")
            if (code == 408 || code == 429 || code in 500..599) throw IOException("Temporary server error: HTTP $code")
            if (code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("Server stopped supporting byte ranges (HTTP $code)")
            }
            val returnedRange = connection.getHeaderField("Content-Range").orEmpty()
            val returnedStart = Regex("""bytes (\d+)-(\d+)/(\d+)""").find(returnedRange)
            val actualStart = returnedStart?.groupValues?.getOrNull(1)?.toLongOrNull()
            val actualEnd = returnedStart?.groupValues?.getOrNull(2)?.toLongOrNull()
            val actualTotal = returnedStart?.groupValues?.getOrNull(3)?.toLongOrNull()
            if (actualStart != rangeStart + offset || actualEnd != rangeEnd || actualTotal != total) {
                throw IOException("Server returned an invalid byte range")
            }
            val responseEtag = connection.getHeaderField("ETag").orEmpty()
            val responseModified = connection.getHeaderField("Last-Modified").orEmpty()
            if ((expectedEtag.isNotBlank() && responseEtag != expectedEtag) ||
                (expectedEtag.isBlank() && expectedModified.isNotBlank() && responseModified != expectedModified)
            ) {
                throw IOException("File changed during segmented download; retry with a fresh source")
            }
            connection.inputStream.use { input ->
                RandomAccessFile(target, "rw").use { output ->
                    output.seek(rangeStart + offset)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastSaved = offset
                    try {
                        while (true) {
                            if (isStopped() || Thread.currentThread().isInterrupted) {
                                throw InterruptedException("Segment download stopped")
                            }
                            val count = input.read(buffer)
                            if (count < 0) break
                            speedLimiter.acquire(count)
                            output.write(buffer, 0, count)
                            offset += count
                            onBytes(count.toLong())
                            if (offset - lastSaved >= 1024L * 1024L) {
                                output.fd.sync()
                                writeSegmentProgress(progressFile, offset)
                                lastSaved = offset
                            }
                        }
                    } finally {
                        output.fd.sync()
                        writeSegmentProgress(progressFile, offset)
                    }
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
        (URL(item.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20000
            readTimeout = 30000
            requestMethod = "GET"
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Range", "bytes=$start-$end")
            if (item.userAgent.isNotBlank()) setRequestProperty("User-Agent", item.userAgent)
            if (item.cookie.isNotBlank()) setRequestProperty("Cookie", item.cookie)
            if (item.referrer.isNotBlank()) setRequestProperty("Referer", item.referrer)
        }

    private fun readSegmentProgress(file: File, maximum: Long): Long =
        if (!file.isFile) 0L else file.readText().trim().toLongOrNull()?.coerceIn(0L, maximum) ?: 0L

    private fun writeSegmentProgress(file: File, bytes: Long) {
        FileOutputStream(file, false).use { output ->
            output.write(bytes.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun clearSegments(directory: File, id: Long) {
        (0 until SEGMENT_COUNT).forEach { index ->
            // Remove legacy per-segment data files and the current progress sidecars.
            File(directory, "$id.seg$index").delete()
            File(directory, "$id.seg$index.progress").delete()
        }
    }
}
