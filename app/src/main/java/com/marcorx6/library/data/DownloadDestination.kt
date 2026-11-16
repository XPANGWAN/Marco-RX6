package com.marcorx6.library.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/**
 * Saves completed downloads in the user's selected SAF folder or in
 * Download/MARCO RX6 by default. No broad storage permission is required.
 */
internal object DownloadDestination {
    private const val DEFAULT_FOLDER = "MARCO RX6"
    private const val MARKER_NAME = "MARCO RX6 folder.txt"

    fun ensureDefaultFolder(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.applicationContext.contentResolver
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$DEFAULT_FOLDER/"
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val existing = runCatching {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(MARKER_NAME, relativePath),
                    null
                )?.use { it.moveToFirst() } ?: false
            }.getOrDefault(false)
            if (existing) return true

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, MARKER_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val marker = resolver.insert(collection, values) ?: return false
            return try {
                resolver.openOutputStream(marker, "w")?.use { stream ->
                    stream.write("This folder is used by MARCO RX6 for downloaded files.".toByteArray(Charsets.UTF_8))
                } 
                    ?: throw IOException("Could not create the default download folder marker")
                val publish = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(marker, publish, null, null) > 0
            } catch (error: Exception) {
                runCatching { resolver.delete(marker, null, null) }
                false
            }
        }

        // Scoped storage is not available before Android 10. Keep the fallback
        // inside the app-specific external Downloads directory.
        val fallback = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
            DEFAULT_FOLDER
        )
        return fallback.exists() || fallback.mkdirs()
    }

    /**
     * Opens the actual final destination. SAF destinations are created immediately
     * in the selected folder; the default Android 10+ destination uses MediaStore.
     */
    fun openTarget(
        context: Context,
        id: Long,
        selectedTree: Uri?,
        displayName: String,
        mimeType: String
    ): Uri? {
        if (selectedTree != null) {
            return openTreeTarget(context, id, selectedTree, displayName, mimeType)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return openDirectTarget(context, id, displayName, mimeType)
    }

    private fun openTreeTarget(
        context: Context,
        id: Long,
        tree: Uri,
        displayName: String,
        mimeType: String
    ): Uri {
        val resolver = context.applicationContext.contentResolver
        val sidecar = File(context.filesDir, "MARCO_RX6_downloads/$id.destination")
        sidecar.parentFile?.mkdirs()
        val previous = runCatching {
            if (sidecar.isFile) Uri.parse(sidecar.readText().trim()) else null
        }.getOrNull()
        if (previous != null && previous.scheme == "content") {
            val belongsToSelectedTree = runCatching {
                previous.authority == tree.authority &&
                    DocumentsContract.getDocumentId(previous).let { documentId ->
                        val treeId = DocumentsContract.getTreeDocumentId(tree)
                        documentId == treeId || documentId.startsWith("$treeId/")
                    }
            }.getOrDefault(false)
            val reusable = belongsToSelectedTree && runCatching {
                resolver.openOutputStream(previous, "wa")?.use { true } ?: false
            }.getOrDefault(false)
            if (reusable) return previous
            deleteTarget(context, previous)
        }
        sidecar.delete()
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
        val target = DocumentsContract.createDocument(
            resolver,
            parent,
            mimeType.ifBlank { "application/octet-stream" },
            displayName
        ) ?: throw IOException("Could not create the download in the selected folder")
        try {
            sidecar.writeText(target.toString())
            resolver.openOutputStream(target, "wt")?.close()
                ?: throw IOException("The selected folder does not allow writing to the destination")
            return target
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            sidecar.delete()
            throw error
        }
    }

    /**
     * Creates or reopens the visible MediaStore file used as the actual download target.
     * The URI is kept in app-private metadata so paused downloads can resume in place.
     */
    fun openDirectTarget(context: Context, id: Long, displayName: String, mimeType: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.applicationContext.contentResolver
        val sidecar = File(context.filesDir, "MARCO_RX6_downloads/$id.destination")
        sidecar.parentFile?.mkdirs()
        val previous = runCatching { if (sidecar.isFile) Uri.parse(sidecar.readText().trim()) else null }.getOrNull()
        if (previous != null && previous.scheme == "content" && previous.authority == MediaStore.AUTHORITY) {
            val reopened = runCatching {
                resolver.openFileDescriptor(previous, "rw")?.use { true } ?: false
            }.getOrDefault(false)
            if (reopened) {
                // Older app versions may have left the row pending. Re-publish it
                // on every resume so file managers can see the in-progress file.
                if (runCatching {
                    publishAndVerify(context, previous, displayName, mimeType)
                    true
                }.getOrDefault(false)) {
                    return previous
                }
            }
        }

        if (previous != null) deleteTarget(context, previous)
        sidecar.delete()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DEFAULT_FOLDER/")
            // Keep the row private until its descriptor and destination metadata
            // have both been verified, then publish it before returning to the queue.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
        ) ?: throw IOException("Could not create the visible preallocated download file")
        return try {
            // Persist the URI before opening the descriptor so a process death
            // cannot leave an untracked MediaStore row behind.
            sidecar.writeText(uri.toString())
            resolver.openFileDescriptor(uri, "rw")?.close()
                ?: throw IOException("Could not open the destination file for random access")
            publishAndVerify(context, uri, displayName, mimeType)
            uri
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    /**
     * Publish the MediaStore row and verify the exact public destination before
     * the scheduler is allowed to start writing. notifyChange nudges file-manager
     * observers after the row becomes visible.
     */
    private fun publishAndVerify(
        context: Context,
        uri: Uri,
        displayName: String,
        mimeType: String
    ) {
        val resolver = context.applicationContext.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$DEFAULT_FOLDER/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        if (resolver.update(uri, values, null, null) <= 0) {
            throw IOException("Could not publish the download in Download/$DEFAULT_FOLDER")
        }
        resolver.notifyChange(uri, null)
        val visible = resolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.IS_PENDING
            ),
            null,
            null,
            null
        )?.use { cursor ->
            cursor.moveToFirst() &&
                cursor.getString(0) == displayName &&
                cursor.getString(1)?.trimEnd('/') == relativePath.trimEnd('/') &&
                (cursor.isNull(2) || cursor.getInt(2) == 0)
        } ?: false
        if (!visible) {
            throw IOException("MediaStore did not confirm the visible file in Download/$DEFAULT_FOLDER")
        }
    }

    fun isMediaStoreTarget(uri: Uri): Boolean = uri.authority == MediaStore.AUTHORITY

    fun directTargetSize(context: Context, uri: Uri, fallback: Long = -1L): Long {
        val resolver = context.contentResolver
        if (!isMediaStoreTarget(uri)) {
            val size = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                }
            }.getOrNull()
            if (size != null && size >= 0L) return size
            val fdSize = runCatching {
                resolver.openFileDescriptor(uri, "rw")?.use { android.system.Os.fstat(it.fileDescriptor).st_size }
            }.getOrNull()
            return fdSize?.takeIf { it >= 0L } ?: fallback
        }
        val descriptor = resolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("Could not reopen the visible download file")
        return descriptor.use { android.system.Os.fstat(it.fileDescriptor).st_size }
    }

    fun openSequentialTargetWriter(context: Context, uri: Uri, offset: Long): java.io.OutputStream {
        if (isMediaStoreTarget(uri)) throw IllegalArgumentException("MediaStore targets use positional writes")
        return context.contentResolver.openOutputStream(uri, if (offset > 0L) "wa" else "wt")
            ?: throw IOException("The selected folder could not open the download for writing")
    }

    fun syncSequentialTargetWriter(output: java.io.OutputStream) {
        output.flush()
        if (output is java.io.FileOutputStream) output.fd.sync()
    }

    fun renameDirectTarget(context: Context, id: Long, uri: Uri, displayName: String): Uri {
        val resolver = context.contentResolver
        val renamed = if (uri.authority == MediaStore.AUTHORITY) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, displayName) }
            if (resolver.update(uri, values, null, null) <= 0) {
                throw IOException("Could not update the visible download filename")
            }
            uri
        } else {
            // Some SAF providers do not implement rename. Keep the valid URI and
            // complete the transfer rather than failing after all bytes were received.
            runCatching { DocumentsContract.renameDocument(resolver, uri, displayName) }
                .getOrNull() ?: uri
        }
        File(context.filesDir, "MARCO_RX6_downloads/$id.destination").writeText(renamed.toString())
        return renamed
    }

    fun truncateDirectTarget(context: Context, uri: Uri, length: Long) {
        val resolver = context.contentResolver
        if (!isMediaStoreTarget(uri)) {
            val ok = runCatching {
                resolver.openFileDescriptor(uri, "rw")?.use {
                    android.system.Os.ftruncate(it.fileDescriptor, length)
                    android.system.Os.fsync(it.fileDescriptor)
                } ?: throw IOException("No file descriptor")
                true
            }.getOrDefault(false)
            if (ok) return
            if (length == 0L) {
                resolver.openOutputStream(uri, "wt")?.use { syncSequentialTargetWriter(it) }
                    ?: throw IOException("Could not restart the file in the selected folder")
                return
            }
            throw IOException("The selected folder cannot safely trim an interrupted file")
        }
        val descriptor = resolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("Could not reopen the visible download file")
        descriptor.use {
            android.system.Os.ftruncate(it.fileDescriptor, length)
            android.system.Os.fsync(it.fileDescriptor)
        }
    }

    fun prepareDirectTarget(
        context: Context,
        uri: Uri,
        total: Long,
        onPreparing: (Int) -> Unit,
        preserveBytes: Long = 0L
    ) {
        if (!isMediaStoreTarget(uri)) throw IllegalArgumentException("Physical preallocation requires MediaStore")
        if (total <= 0L) { onPreparing(100); return }
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("Could not open the visible download file")
        descriptor.use { pfd ->
            android.system.Os.ftruncate(pfd.fileDescriptor, total)
            val zeroes = ByteArray(1024 * 1024)
            var prepared = preserveBytes.coerceIn(0L, total)
            var fallocateSupported = true
            val segmentSize = ((total - 1L) / 4L) + 1L
            fun zeroRange(start: Long, length: Long) {
                var position = start
                val end = start + length
                while (position < end) {
                    val count = minOf(zeroes.size.toLong(), end - position).toInt()
                    var written = 0
                    while (written < count) {
                        val n = android.system.Os.pwrite(pfd.fileDescriptor, zeroes, written, count - written, position + written)
                        if (n <= 0) throw IOException("Could not reserve storage for the download")
                        written += n
                    }
                    position += count
                    prepared += count
                    onPreparing((prepared.toDouble() * 100.0 / total.toDouble()).toInt().coerceIn(1, 100))
                }
            }
            for (index in 0 until 4) {
                val start = index.toLong() * segmentSize
                if (start >= total) continue
                val length = minOf(total - start, segmentSize)
                if (fallocateSupported) {
                    try {
                        android.system.Os.posix_fallocate(pfd.fileDescriptor, start, length)
                        prepared += (start + length - maxOf(start, preserveBytes.coerceIn(0L, total))).coerceAtLeast(0L)
                        onPreparing((prepared.toDouble() * 100.0 / total).toInt().coerceIn(1, 100))
                        continue
                    } catch (error: android.system.ErrnoException) {
                        val unsupported = error.errno == android.system.OsConstants.EOPNOTSUPP ||
                            error.errno == android.system.OsConstants.ENOSYS ||
                            error.errno == android.system.OsConstants.EINVAL
                        if (!unsupported) throw error
                        fallocateSupported = false
                    }
                }
                val zeroStart = maxOf(start, preserveBytes.coerceIn(0L, total))
                if (zeroStart < start + length) zeroRange(zeroStart, start + length - zeroStart)
            }
            android.system.Os.fsync(pfd.fileDescriptor)
        }
    }

    internal class DirectWriter(context: Context, uri: Uri) : java.io.Closeable {
        private val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("Could not open the visible download file for writing")

        fun writeAt(bytes: ByteArray, count: Int, position: Long) {
            var written = 0
            while (written < count) {
                val amount = android.system.Os.pwrite(
                    descriptor.fileDescriptor, bytes, written, count - written, position + written
                )
                if (amount <= 0) throw IOException("Could not write to the preallocated destination")
                written += amount
            }
        }

        fun sync() = android.system.Os.fsync(descriptor.fileDescriptor)

        override fun close() = descriptor.close()
    }

    fun openDirectWriter(context: Context, uri: Uri): DirectWriter = DirectWriter(context, uri)

    private fun deleteTarget(context: Context, uri: Uri) {
        runCatching {
            if (uri.authority == MediaStore.AUTHORITY) context.contentResolver.delete(uri, null, null)
            else DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
    }

    fun removeDirectTarget(context: Context, id: Long) {
        val sidecar = File(context.filesDir, "MARCO_RX6_downloads/$id.destination")
        val uri = runCatching { if (sidecar.isFile) Uri.parse(sidecar.readText().trim()) else null }.getOrNull()
        if (uri != null) deleteTarget(context, uri)
        sidecar.delete()
    }

    fun forgetDirectTarget(context: Context, id: Long) {
        File(context.filesDir, "MARCO_RX6_downloads/$id.destination").delete()
    }

    fun save(
        context: Context,
        selectedTree: Uri?,
        source: File,
        displayName: String,
        mimeType: String,
        expectedBytes: Long
    ): String {
        if (!source.isFile) throw IOException("Download source file does not exist")
        if (expectedBytes > 0L && source.length() != expectedBytes) {
            throw IOException("Download source size mismatch: ${source.length()} / $expectedBytes")
        }

        if (selectedTree != null) {
            val resolver = context.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                selectedTree,
                DocumentsContract.getTreeDocumentId(selectedTree)
            )
            val target = DocumentsContract.createDocument(
                resolver,
                parent,
                mimeType.ifBlank { "application/octet-stream" },
                displayName
            ) ?: throw IOException("Could not create a file in the selected download folder")
            return try {
                val copied = resolver.openOutputStream(target, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException("Could not write to the selected download folder")
                if (expectedBytes > 0L && copied != expectedBytes) {
                    throw IOException("Saved file size mismatch: $copied / $expectedBytes")
                }
                target.toString()
            } catch (error: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, target) }
                throw error
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.applicationContext.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/$DEFAULT_FOLDER/"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val target = resolver.insert(collection, values)
                ?: throw IOException("Could not create the file in Download/$DEFAULT_FOLDER")
            return try {
                val copied = resolver.openOutputStream(target, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException("Could not write the downloaded file")
                if (expectedBytes > 0L && copied != expectedBytes) {
                    throw IOException("Saved file size mismatch: $copied / $expectedBytes")
                }
                val savedSize = resolver.query(
                    target,
                    arrayOf(OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else copied
                } ?: copied
                if (expectedBytes > 0L && savedSize != expectedBytes) {
                    throw IOException("Final file size mismatch: $savedSize / $expectedBytes")
                }
                val publish = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                if (resolver.update(target, publish, null, null) <= 0) {
                    throw IOException("Could not finalize the downloaded file")
                }
                target.toString()
            } catch (error: Exception) {
                runCatching { resolver.delete(target, null, null) }
                throw error
            }
        }

        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
            DEFAULT_FOLDER
        )
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create the app download folder")
        }
        val output = File(directory, displayName)
        source.copyTo(output, overwrite = true)
        if (expectedBytes > 0L && output.length() != expectedBytes) {
            output.delete()
            throw IOException("Saved file size mismatch: ${output.length()} / $expectedBytes")
        }
        return output.absolutePath
    }

    fun delete(context: Context, location: String) {
        if (location.isBlank()) return
        if (location.startsWith("content://")) {
            val uri = Uri.parse(location)
            runCatching {
                if (uri.authority == MediaStore.AUTHORITY) {
                    context.contentResolver.delete(uri, null, null)
                } else {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                }
            }
        } else {
            runCatching { File(location).delete() }
        }
    }
}
