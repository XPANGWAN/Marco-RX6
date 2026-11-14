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
