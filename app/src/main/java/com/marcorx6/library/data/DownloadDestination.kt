package com.marcorx6.library.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.ContentUris
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
        val previous = try {
            if (sidecar.isFile) {
                val saved = sidecar.readText().trim()
                Uri.parse(saved).takeIf { saved.isNotBlank() }
                    ?: throw IOException("The saved download destination checkpoint is empty")
            } else null
        } catch (error: Exception) {
            throw IOException("The original download destination could not be recovered; no duplicate file was created", error)
        }
        if (previous != null) {
            if (previous.scheme != "content") {
                throw IOException("The original download destination is invalid; it was preserved for recovery")
            }
            val belongsToSelectedTree = runCatching {
                previous.authority == tree.authority &&
                    DocumentsContract.getDocumentId(previous).let { documentId ->
                        val treeId = DocumentsContract.getTreeDocumentId(tree)
                        documentId == treeId || documentId.startsWith("$treeId/")
                    }
            }.getOrDefault(false)
            if (!belongsToSelectedTree) {
                // A folder preference change must never silently move a paused
                // download into a new document and leave the original behind.
                throw IOException("This download belongs to its original folder. Restore that folder to resume the same file.")
            }
            val reusable = runCatching {
                resolver.openFileDescriptor(previous, "rw")?.use { true } ?: false
            }.getOrDefault(false)
            if (!reusable) {
                throw IOException("The existing download file in the selected folder cannot be reopened; its original destination was preserved")
            }
            return previous
        }
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
            persistDestinationUri(context, id, target)
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
        val previous = runCatching {
            if (sidecar.isFile) Uri.parse(sidecar.readText().trim()) else null
        }.getOrNull()

        if (previous != null && previous.scheme == "content") {
            if (previous.authority != MediaStore.AUTHORITY) {
                // The user changed storage destinations while this item was paused.
                // Never delete the old document or silently create a second copy.
                throw IOException("This download is linked to its original selected folder. Restore that folder to resume the same file.")
            }
            val previousExists = resolver.query(
                previous, arrayOf(MediaStore.MediaColumns._ID), null, null, null
            )?.use { it.moveToFirst() }
                ?: throw IOException("Could not verify the existing download destination")
            if (previousExists) {
                val reopened = runCatching {
                    resolver.openFileDescriptor(previous, "rw")?.use { true } ?: false
                }.getOrDefault(false)
                if (!reopened) {
                    throw IOException("The existing download file is present but cannot be reopened; its destination was preserved")
                }
                try {
                    publishAndVerify(context, id, previous, displayName, mimeType)
                    return previous
                } catch (error: Exception) {
                    throw IOException(
                        "Could not verify the existing download destination; the same file was preserved for retry",
                        error
                    )
                }
            }
        }

        // Recover the original MediaStore row if its small private URI checkpoint
        // was lost or became stale. Each row receives an app-owned marker, so this
        // lookup cannot accidentally adopt another download with the same filename.
        val recovered = findRecoverableDirectTarget(context, id, displayName)
        if (recovered != null) {
            persistDestinationUri(context, id, recovered)
            publishAndVerify(context, id, recovered, displayName, mimeType)
            return recovered
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DEFAULT_FOLDER/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
        ) ?: throw IOException("Could not create the visible preallocated download file")
        return try {
            // Commit the URI checkpoint before opening or publishing the row.
            persistDestinationUri(context, id, uri)
            resolver.openFileDescriptor(uri, "rw")?.close()
                ?: throw IOException("Could not open the destination file for random access")
            publishAndVerify(context, id, uri, displayName, mimeType)
            uri
        } catch (error: Exception) {
            // Keep the URI checkpoint if the row still exists: a retry must inspect
            // and reuse this exact row rather than create a '(1)' duplicate.
            throw error
        }
    }

    @SuppressLint("NewApi")
    private fun findRecoverableDirectTarget(context: Context, id: Long, displayName: String): Uri? {
        val resolver = context.applicationContext.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$DEFAULT_FOLDER/"
        fun findOne(selection: String, args: Array<String>): Uri? {
            return resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                args,
                "${MediaStore.MediaColumns._ID} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val rowId = cursor.getLong(0)
                if (cursor.moveToNext()) return@use null
                ContentUris.withAppendedId(collection, rowId)
            }
        }

        // Compatibility recovery for older rows without a private URI checkpoint.
        // Reuse only one exact-name match; never guess when multiple rows exist.
        return findOne(
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(displayName, relativePath)
        )
    }

    /**
     * Publish the MediaStore row and verify the exact public destination before
     * the scheduler is allowed to start writing. notifyChange nudges file-manager
     * observers after the row becomes visible.
     */
    private fun publishAndVerify(
        context: Context,
        id: Long,
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

    /**
     * SAF providers are not guaranteed to implement append as a seek to the
     * requested resume offset. Open the document descriptor in read/write mode,
     * seek explicitly, and fail closed when the provider is not seekable.
     */
    internal class SeekableTargetWriter(
        private val descriptor: android.os.ParcelFileDescriptor,
        offset: Long
    ) : java.io.OutputStream() {
        private val stream = java.io.FileOutputStream(descriptor.fileDescriptor)

        init {
            try {
                android.system.Os.lseek(
                    descriptor.fileDescriptor,
                    offset,
                    android.system.OsConstants.SEEK_SET
                )
            } catch (error: Exception) {
                runCatching { stream.close() }
                throw IOException("The selected folder cannot safely resume at byte $offset; the original file was preserved", error)
            }
        }

        override fun write(value: Int) = stream.write(value)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = stream.write(bytes, offset, length)
        override fun flush() = stream.flush()
        fun sync() {
            stream.flush()
            android.system.Os.fsync(descriptor.fileDescriptor)
        }
        override fun close() = stream.close()
    }

    fun openSequentialTargetWriter(context: Context, uri: Uri, offset: Long): SeekableTargetWriter {
        if (isMediaStoreTarget(uri)) throw IllegalArgumentException("MediaStore targets use positional writes")
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("The selected folder could not open the download for writing")
        return try {
            SeekableTargetWriter(descriptor, offset)
        } catch (error: Exception) {
            runCatching { descriptor.close() }
            throw error
        }
    }

    fun syncSequentialTargetWriter(output: java.io.OutputStream) {
        if (output is SeekableTargetWriter) output.sync()
        else {
            output.flush()
            if (output is java.io.FileOutputStream) output.fd.sync()
        }
    }

    fun renameDirectTarget(context: Context, id: Long, uri: Uri, displayName: String): Uri {
        val resolver = context.contentResolver
        val renamed = if (uri.authority == MediaStore.AUTHORITY) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            }
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
        persistDestinationUri(context, id, renamed)
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

    /**
     * Reserve the final destination before the first network byte is written.
     *
     * This deliberately supports both MediaStore and user-selected SAF documents.
     * SAF providers vary: use posix_fallocate where available, then physically
     * initialize the remaining range with bounded zero writes when allocation is
     * unsupported. If neither path can reserve the bytes, fail before transfer
     * instead of silently allowing a file that grows during download.
     */
    fun prepareDirectTarget(
        context: Context,
        uri: Uri,
        total: Long,
        onPreparing: (Int) -> Unit,
        preserveBytes: Long = 0L,
        shouldStop: () -> Boolean = { false }
    ) {
        if (total <= 0L) throw IOException("The server did not provide a valid total file size")

        val preserved = preserveBytes.coerceIn(0L, total)
        val resolver = context.applicationContext.contentResolver
        val descriptor = resolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("Could not open the destination file for full-size reservation")

        descriptor.use { pfd ->
            val fd = pfd.fileDescriptor
            try {
                android.system.Os.ftruncate(fd, total)
            } catch (error: Exception) {
                throw IOException("The selected folder cannot set the final file size before downloading", error)
            }
            if (android.system.Os.fstat(fd).st_size != total) {
                throw IOException("The destination did not accept the final file length")
            }

            var position = preserved
            var needsZeroWriteReservation = false
            val chunkSize = 8L * 1024L * 1024L

            while (position < total) {
                if (shouldStop()) throw InterruptedException("Download preparation stopped")
                val count = minOf(chunkSize, total - position)
                try {
                    android.system.Os.posix_fallocate(fd, position, count)
                } catch (error: android.system.ErrnoException) {
                    val unsupported =
                        error.errno == android.system.OsConstants.EOPNOTSUPP ||
                        error.errno == android.system.OsConstants.ENOSYS ||
                        error.errno == android.system.OsConstants.EINVAL
                    if (!unsupported) {
                        throw IOException("Storage could not reserve the requested download space", error)
                    }
                    needsZeroWriteReservation = true
                    break
                }
                position += count
                onPreparing((position.toDouble() * 100.0 / total.toDouble()).toInt().coerceIn(1, 99))
            }

            if (needsZeroWriteReservation) {
                // Sequential writes are more compatible with document-provider
                // proxy descriptors than pwrite; memory stays bounded to 1 MiB.
                val zeroes = ByteArray(1024 * 1024)
                android.system.Os.lseek(fd, preserved, android.system.OsConstants.SEEK_SET)
                position = preserved
                while (position < total) {
                    if (shouldStop()) throw InterruptedException("Download preparation stopped")
                    val count = minOf(zeroes.size.toLong(), total - position).toInt()
                    var written = 0
                    while (written < count) {
                        if (shouldStop()) throw InterruptedException("Download preparation stopped")
                        val amount = android.system.Os.write(fd, zeroes, written, count - written)
                        if (amount <= 0) {
                            throw IOException("Storage stopped accepting bytes before the full file was reserved")
                        }
                        written += amount
                    }
                    position += count
                    onPreparing((position.toDouble() * 100.0 / total.toDouble()).toInt().coerceIn(1, 99))
                }
            }

            if (shouldStop()) throw InterruptedException("Download preparation stopped")
            android.system.Os.fsync(fd)
            val stat = android.system.Os.fstat(fd)
            if (stat.st_size != total) {
                throw IOException("Reserved file length verification failed: ${stat.st_size} / $total")
            }
        }

        // Some providers expose SIZE only after the writable descriptor closes.
        // If a provider reports a size, it must match; unknown SIZE is checked by fstat above.
        val indexedSize = resolver.query(
            uri, arrayOf(OpenableColumns.SIZE), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
        if (indexedSize >= 0L && indexedSize != total) {
            throw IOException("The destination provider did not confirm the full file size: $indexedSize / $total")
        }

        if (isMediaStoreTarget(uri)) {
            val publish = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (resolver.update(uri, publish, null, null) <= 0) {
                throw IOException("Could not publish the fully prepared download file")
            }
            resolver.notifyChange(uri, null)
        }

        val visibleSize = resolver.query(
            uri, arrayOf(OpenableColumns.SIZE), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
        if (visibleSize >= 0L && visibleSize != total) {
            throw IOException("The destination is not reporting the reserved file size: $visibleSize / $total")
        }
        onPreparing(100)
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

    /**
     * Persist the destination URI with a same-directory atomic rename. A process
     * death must not leave a half-written checkpoint that causes resume to create
     * another visible MediaStore/SAF file.
     */
    private fun persistDestinationUri(context: Context, id: Long, uri: Uri) {
        val sidecar = File(context.filesDir, "MARCO_RX6_downloads/$id.destination")
        sidecar.parentFile?.mkdirs()
        val temporary = File(sidecar.parentFile, sidecar.name + ".tmp")
        java.io.FileOutputStream(temporary, false).use { output ->
            output.write(uri.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(sidecar)) {
            temporary.delete()
            throw IOException("Could not commit the download destination checkpoint")
        }
    }

    private fun deleteTarget(context: Context, uri: Uri) {
        runCatching {
            if (uri.authority == MediaStore.AUTHORITY) context.contentResolver.delete(uri, null, null)
            else DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
    }

    /**
     * Reset an interrupted direct download in place. Keep its MediaStore row and
     * sidecar URI so a range-to-sequential fallback cannot create a duplicate file.
     */
    fun resetDirectTarget(context: Context, id: Long) {
        val sidecar = File(context.filesDir, "MARCO_RX6_downloads/$id.destination")
        val uri = runCatching {
            if (sidecar.isFile) Uri.parse(sidecar.readText().trim()) else null
        }.getOrNull() ?: return
        truncateDirectTarget(context, uri, 0L)
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
