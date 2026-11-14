package com.marcorx6.library.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Saves completed downloads in the user's selected folder, or Download/MARCO RX6 by default. */
internal object DownloadDestination {
    fun save(
        context: Context,
        selectedTree: Uri?,
        source: File,
        fileName: String,
        mimeType: String,
        expectedBytes: Long
    ): String {
        val resolver = context.contentResolver
        if (selectedTree != null) {
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                selectedTree, DocumentsContract.getTreeDocumentId(selectedTree)
            )
            val target = DocumentsContract.createDocument(
                resolver, parentUri, mimeType.ifBlank { "application/octet-stream" }, fileName
            ) ?: throw IOException("Could not create a file in the selected download folder")
            try {
                val copied = resolver.openOutputStream(target, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException("Could not write to the selected download folder")
                verify(copied, expectedBytes)
                return target.toString()
            } catch (error: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, target) }
                throw error
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MARCO RX6")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val target = resolver.insert(collection, values)
                ?: throw IOException("Could not create Download/MARCO RX6")
            try {
                val copied = resolver.openOutputStream(target, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException("Could not write to Download/MARCO RX6")
                verify(copied, expectedBytes)
                val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                if (resolver.update(target, ready, null, null) != 1) {
                    throw IOException("Could not finalize the saved download")
                }
                return target.toString()
            } catch (error: Exception) {
                runCatching { resolver.delete(target, null, null) }
                throw error
            }
        }

        // Legacy Android fallback: retain the app's private download storage.
        val directory = File(context.filesDir, "MARCO_RX6_downloads").apply { mkdirs() }
        val output = File(directory, fileName)
        if (output.exists()) output.delete()
        source.copyTo(output, overwrite = true)
        verify(output.length(), expectedBytes)
        return output.absolutePath
    }

    fun delete(context: Context, location: String) {
        if (location.startsWith("content://")) {
            runCatching { context.contentResolver.delete(Uri.parse(location), null, null) }
        } else if (location.isNotBlank()) {
            File(location).delete()
        }
    }

    private fun verify(actual: Long, expected: Long) {
        if (expected > 0L && actual != expected) {
            throw IOException("Saved file size mismatch: $actual / $expected")
        }
    }
}
