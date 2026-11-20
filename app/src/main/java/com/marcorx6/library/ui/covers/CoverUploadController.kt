package com.marcorx6.library.ui

import android.net.Uri
import com.marcorx6.library.data.CoverUploadRepository
import com.marcorx6.library.data.CoverUploadResult
import java.io.File

internal class CoverUploadController(
    private val repository: CoverUploadRepository,
    private val accessToken: () -> String,
    private val executeIo: (() -> Unit) -> Unit,
    private val postToUi: (() -> Unit) -> Unit
) {
    fun uploadGalleryImages(
        uris: List<Uri>,
        onComplete: (List<String>, String?) -> Unit
    ) {
        executeIo {
            val urls = mutableListOf<String>()
            var failure: String? = null
            for (uri in uris) {
                var localFile: File? = null
                try {
                    val result = repository.saveAndUploadCover(uri, accessToken()) { localFile = it }
                    urls += result.publicUrl
                } catch (error: Exception) {
                    failure = error.message ?: "فشل رفع إحدى صور المعرض"
                    break
                } finally {
                    localFile?.delete()
                }
            }
            postToUi { onComplete(urls, failure) }
        }
    }

    fun upload(
        uri: Uri,
        onLocalFileSaved: (File) -> Unit,
        onSuccess: (CoverUploadResult) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        executeIo {
            try {
                val result = repository.saveAndUploadCover(uri, accessToken()) { localFile ->
                    postToUi { onLocalFileSaved(localFile) }
                }
                postToUi { onSuccess(result) }
            } catch (error: Exception) {
                postToUi { onFailure(error) }
            }
        }
    }
}
