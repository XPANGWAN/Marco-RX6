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
            val reusable = runCatching {
                resolver.openFileDescriptor(previous, "rw")?.use { true } ?: false
            }.getOrDefault(false)
            if (reusable) return previous
            runCatching { DocumentsContract.deleteDocument(resolver, previous) }
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
            resolver.openFileDescriptor(target, "rw")?.close()
                ?: throw IOException("The selected folder does not support direct file access")
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
        if (previous != null && previous.scheme == "content") {
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

        if (previous != null) runCatching { resolver.delete(previous, null, null) }
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

    fun directTargetSize(context: Context, uri: Uri): Long {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("Could not reopen the preallocated download file")
        return descriptor.use { android.system.Os.fstat(it.fileDescriptor).st_size }
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
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("Could not reopen the visible download file")
        descriptor.use {
            android.system.Os.ftruncate(it.fileDescriptor, length)
            android.system.Os.fsync(it.fileDescriptor)
        }
    }

    fun prepareDirectTarget(context: Context, uri: Uri, total: Long, onPreparing: (Int) -> Unit) {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("Could not open the visible download file")
        descriptor.use { pfd ->
            android.system.Os.ftruncate(pfd.fileDescriptor, total)
            var prepared = 0L
            var supportsPhysicalPreallocation = true
            val segmentSize = ((total - 1L) / 4L) + 1L
            for (index in 0 until 4) {
                val start = index * segmentSize
                if (start >= total) continue
                val length = minOf(total - start, segmentSize)
                if (supportsPhysicalPreallocation) {
                    try {
                        android.system.Os.posix_fallocate(pfd.fileDescriptor, start, length)
                    } catch (error: android.system.ErrnoException) {
                        // MediaStore/FUSE providers may support positional writes and
                        // truncation but not Linux fallocate. Keep the visible target.
                        val unsupported = error.errno == android.system.OsConstants.EOPNOTSUPP ||
                            error.errno == android.system.OsConstants.ENOSYS ||
                            error.errno == android.system.OsConstants.EINVAL
                        if (!unsupported) throw error
                        supportsPhysicalPreallocation = false
                    }
                }
                prepared += length
                onPreparing((prepared.toDouble() * 100.0 / total.toDouble()).toInt().coerceIn(1, 100))
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

    fun removeDirectTarget(context: Context, id: Long) {
        val sidecar = File(context.filesDir, "MARCO_RX6_downloads/$id.destination")
        val uri = runCatching { if (sidecar.isFile) Uri.parse(sidecar.readText().trim()) else null }.getOrNull()
        if (uri != null) runCatching {
            if (uri.authority == MediaStore.AUTHORITY) context.contentResolver.delete(uri, null, null)
            else DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
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
            runCatching { context.contentResolver.delete(Uri.parse(location), null, null) }
        } else {
            runCatching { File(location).delete() }
        }
    }
}
