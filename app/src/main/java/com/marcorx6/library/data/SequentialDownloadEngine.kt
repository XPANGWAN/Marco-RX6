package com.marcorx6.library.data

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** Sequential transfer engine with durable checkpoints and an explicit destination writer. */
internal object SequentialDownloadEngine {
        fun download(context: Context, item: StoredDownload, database: DownloadDatabase, shouldStop: () -> Boolean, onProgress: (Long, Long) -> Unit, onPreparing: (Int) -> Unit) {
            val directory = File(context.filesDir, "MARCO_RX6_downloads").apply { mkdirs() }
            val partial = File(directory, "${item.id}.part")
            val durableProgress = File(directory, "${item.id}.part.progress")
            // All transfers use one deterministic writer and one durable checkpoint.
            // Multi-range mode stays disabled until it shares this exact destination journal.
            // Write directly to the final destination: MediaStore by default, or the
            // user's selected SAF folder. Only pre-Android-10 without SAF needs staging.
            val selectedTree = DownloadStoragePreferences(context).treeUri()
            var directUri = if (selectedTree != null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                DownloadDestination.openTarget(
                    context, item.id, selectedTree,
                    DownloadFileNames.safeName(item.title, item.mimeType, item.id), item.mimeType
                )
            } else null
            var directSize = 0L
            if (directUri != null) {
                directSize = try {
                    DownloadDestination.directTargetSize(context, directUri)
                } catch (error: Exception) {
                    // Preserve the exact URI checkpoint on every reopen/stat failure.
                    // Deleting it here made the retry create a second MediaStore row.
                    throw IOException(
                        "Could not reopen or inspect the existing visible file in Download/MARCO RX6. The original file was preserved for retry.",
                        error
                    )
                }
            }
            var offset = if (directUri != null) {
                readDurableOffset(durableProgress, item.downloadedBytes, directSize)
            } else if (partial.exists()) {
                readDurableOffset(durableProgress, item.downloadedBytes, partial.length())
            } else 0L
            if (directUri != null && offset == 0L && directSize > 0L) {
                DownloadDestination.truncateDirectTarget(context, directUri, 0L)
                directSize = 0L
            } else if (directUri != null && !DownloadDestination.isMediaStoreTarget(directUri) && directSize > offset) {
                try {
                    DownloadDestination.truncateDirectTarget(context, directUri, offset)
                } catch (_: Exception) {
                    DownloadDestination.truncateDirectTarget(context, directUri, 0L)
                    durableProgress.delete()
                    database.progress(item.id, 0L, item.totalBytes, item.etag, item.lastModified)
                    offset = 0L
                }
                directSize = offset
            }
            // Discard bytes written after the last fsynced checkpoint. They will be
            // downloaded again rather than trusting data that was never committed.
            if (directUri == null && partial.exists() && partial.length() != offset) {
                RandomAccessFile(partial, "rw").use { it.setLength(offset); it.fd.sync() }
            }
            val speedLimiter = DownloadSpeedLimiter(DownloadConcurrencyPreferences(context).speedLimitBytesPerSecond())
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
                val responseMime = connection.contentType?.substringBefore(';')?.trim().orEmpty()
                if (responseMime.equals("text/html", ignoreCase = true) ||
                    responseMime.equals("application/xhtml+xml", ignoreCase = true)
                ) {
                    throw IOException("The source returned a web page instead of a downloadable file")
                }
                val append = offset > 0L && code == HttpURLConnection.HTTP_PARTIAL
                val responseEtag = connection.getHeaderField("ETag").orEmpty()
                val responseModified = connection.getHeaderField("Last-Modified").orEmpty()
                val responseLength = connection.getHeaderFieldLong("Content-Length", -1L)
                val contentRange = DownloadTransferProtocol.parseContentRange(connection.getHeaderField("Content-Range").orEmpty())
                val rangeTotal = contentRange?.total
    
                if (append) {
                    val verifiedAppend = DownloadTransferProtocol.canAppend(
                        requestedOffset = offset,
                        responseStatus = code,
                        range = contentRange,
                        contentLength = responseLength,
                        expectedEtag = item.etag,
                        responseEtag = responseEtag,
                        expectedLastModified = item.lastModified,
                        responseLastModified = responseModified
                    )
                    if (!verifiedAppend) {
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
                        DownloadDestination.truncateDirectTarget(context, directUri, 0L)
                        directSize = 0L
                    }
                    offset = 0L
                }
                val total = when {
                    append && rangeTotal != null -> rangeTotal
                    responseLength >= 0L -> responseLength
                    else -> -1L
                }
                if (total < 0L) {
                    // A full-size reservation is impossible when the server does not
                    // disclose a reliable total length. Never silently start a growing
                    // file and present it as a preallocated download.
                    throw IllegalStateException("The server did not provide the total file size; safe full-size preallocation is not possible")
                }
                val responseStartOffset = offset
                val integrityVerifier = DownloadIntegrityVerifier.from(connection)
                val etag = connection.getHeaderField("ETag") ?: item.etag
                val modified = connection.getHeaderField("Last-Modified") ?: item.lastModified
                val mime = connection.contentType?.substringBefore(';')?.trim().orEmpty().ifBlank { item.mimeType }
                val responseName = DownloadFileNames.fromResponse(
                    connection.getHeaderField("Content-Disposition"),
                    connection.url.toString()
                )
                val safeName = DownloadFileNames.safeName(responseName ?: item.title, mime, item.id)
                if (directUri != null && total > 0L &&
                    (DownloadDestination.isMediaStoreTarget(directUri) ||
                        directSize != total || item.preparationPercent < 100 ||
                        (responseStartOffset == 0L && item.downloadedBytes > 0L))
                ) {
                    database.preparing(item.id, 0, total, etag, modified)
                    if (DownloadDestination.isMediaStoreTarget(directUri)) {
                        DownloadDestination.prepareDirectTarget(context, directUri, total, { percent ->
                            database.preparing(item.id, percent, total, etag, modified)
                            onPreparing(percent)
                        }, responseStartOffset, shouldStop = {
                            shouldStop() ||
                                database.get(item.id)?.status == DownloadManager.STATUS_PAUSED ||
                                database.get(item.id)?.status == DownloadDatabase.STATUS_CANCELLED
                        })
                    } else {
                        DownloadDestination.openSequentialTargetWriter(context, directUri, responseStartOffset)
                            .use { DownloadDestination.syncSequentialTargetWriter(it) }
                        // SAF providers do not expose a portable physical
                        // preallocation contract. Keep the Preparing phase visible,
                        // but never report 100% reserved when no reservation occurred.
                        database.preparing(item.id, 0, total, etag, modified)
                        updatePreparingNotification(item.title, item.id, 0)
                    }
                }
                // No matter which storage provider or size metadata the server supplies,
                // preparation ends here: immediately before the first response byte is read.
                database.finishPreparing(item.id)
                if (directUri != null) {
                    directUri = DownloadDestination.renameDirectTarget(context, item.id, directUri, safeName)
                }
                database.renameTitle(item.id, safeName)
                database.progress(item.id, offset, total, etag, modified)
                // Persist even a zero-byte checkpoint so retries and pauses keep using
                // this same visible target instead of restarting through the range probe.
                writeDurableOffset(durableProgress, offset)
                var responseBytes = 0L
                connection.inputStream.use { input ->
                    val directWriter = directUri?.takeIf { DownloadDestination.isMediaStoreTarget(it) }
                        ?.let { DownloadDestination.openDirectWriter(context, it) }
                    val sequentialTarget = directUri?.takeUnless { DownloadDestination.isMediaStoreTarget(it) }
                        ?.let { DownloadDestination.openSequentialTargetWriter(context, it, responseStartOffset) }
                    val fileSink = if (directWriter == null && sequentialTarget == null) FileOutputStream(partial, append) else null
                    try {
                        val buffer = ByteArray(64 * 1024)
                        var lastUpdate = System.currentTimeMillis()
                        var lastNotificationUpdate = 0L
                        while (true) {
                            if (shouldStop()) throw InterruptedException("Download paused or cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (total > 0L && count.toLong() > total - offset) {
                                throw IOException("The server sent more bytes than the declared file size")
                            }
                            integrityVerifier?.update(buffer, count)
                            speedLimiter.acquire(count)
                            if (directWriter != null) directWriter.writeAt(buffer, count, offset)
                            else if (sequentialTarget != null) sequentialTarget.write(buffer, 0, count)
                            else fileSink!!.write(buffer, 0, count)
                            offset += count
                            responseBytes += count
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate >= 1_000L) {
                                if (directWriter != null) directWriter.sync()
                                else if (sequentialTarget != null) DownloadDestination.syncSequentialTargetWriter(sequentialTarget)
                                else { fileSink!!.flush(); fileSink!!.fd.sync() }
                                writeDurableOffset(durableProgress, offset)
                                database.progress(item.id, offset, total, etag, modified)
                                lastUpdate = now
                                if (now - lastNotificationUpdate >= 1_500L) {
                                    onProgress(offset, total)
                                    lastNotificationUpdate = now
                                }
                            }
                        }
                        if (directWriter != null) directWriter.sync()
                        else if (sequentialTarget != null) DownloadDestination.syncSequentialTargetWriter(sequentialTarget)
                        else { fileSink!!.flush(); fileSink!!.fd.sync() }
                        writeDurableOffset(durableProgress, offset)
                    } finally {
                        runCatching { fileSink?.close() }
                        runCatching { directWriter?.close() }
                        runCatching { sequentialTarget?.close() }
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
                            DownloadDestination.truncateDirectTarget(context, directUri, responseStartOffset)
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
                if (shouldStop() || database.get(item.id)?.status == DownloadDatabase.STATUS_CANCELLED ||
                    database.get(item.id)?.status == DownloadManager.STATUS_PAUSED) {
                    throw InterruptedException("Download stopped")
                }
                database.progress(item.id, offset, total, etag, modified)
                if (total > 0L && offset != total) {
                    throw IOException("Incomplete file: $offset / $total")
                }
                if (directUri != null && total > 0L &&
                    DownloadDestination.directTargetSize(context, directUri, offset) != total
                ) throw IOException("Final destination size does not match the verified download size")
                // Network transfer is finished. Keep the item RUNNING while finalizing;
                // PREPARING is reserved exclusively for the pre-download storage phase.
                onProgress(offset, total)
                val savedLocation = if (directUri != null) {
                    directUri.toString()
                } else {
                    DownloadDestination.save(
                        context,
                        DownloadStoragePreferences(context).treeUri(),
                        partial,
                        safeName,
                        mime,
                        total
                    )
                }
                database.finishPreparing(item.id)
                if (!database.complete(item.id, savedLocation, mime)) {
                    if (directUri == null) DownloadDestination.delete(context, savedLocation)
                    throw InterruptedException("Download state changed before completion")
                }
                // Only discard resume metadata after the database has committed SUCCESS.
                if (directUri != null) DownloadDestination.forgetDirectTarget(context, item.id)
                partial.delete()
                durableProgress.delete()
                File(directory, durableProgress.name + ".tmp").delete()
                return
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
    
}