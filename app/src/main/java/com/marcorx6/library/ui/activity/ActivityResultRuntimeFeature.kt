package com.marcorx6.library.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Registers and routes the three Activity Result contracts used by the app.
 * Registration is eager so launchers are registered before the Activity reaches STARTED.
 */
internal class ActivityResultRuntimeFeature(
    activity: ComponentActivity,
    onGoogleSignInResult: (Intent?) -> Unit,
    onFirebaseGoogleSignInResult: (Intent?) -> Unit,
    onAvatarSelected: (Uri) -> Unit,
    onCoverSelected: (Uri) -> Unit,
    onGalleryImagesSelected: (List<Uri>) -> Unit,
    onDownloadFolderSelected: (Uri) -> Unit
) {
    companion object {
        private const val SIGN_IN_REQUEST_CODE = 1002
        private const val COVER_REQUEST_CODE = 1001
        private const val AVATAR_REQUEST_CODE = 1005
    }

    private val controller = MainActivityResultController(
        signInRequestCode = SIGN_IN_REQUEST_CODE,
        avatarRequestCode = AVATAR_REQUEST_CODE,
        coverRequestCode = COVER_REQUEST_CODE,
        onGoogleSignInResult = onGoogleSignInResult,
        onAvatarSelected = onAvatarSelected,
        onCoverSelected = onCoverSelected
    )

    private val googleSignInLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            controller.handle(SIGN_IN_REQUEST_CODE, result.resultCode, result.data)
        }

    private val firebaseGoogleSignInLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onFirebaseGoogleSignInResult(result.data)
        }

    private val coverPickerLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            controller.handle(COVER_REQUEST_CODE, result.resultCode, result.data)
        }

    private val profileAvatarLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            controller.handle(AVATAR_REQUEST_CODE, result.resultCode, result.data)
        }

    private val galleryImagesLauncher =
        activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            onGalleryImagesSelected(uris)
        }

    private val downloadFolderPickerLauncher =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(onDownloadFolderSelected)
        }

    fun launchGoogleSignIn(intent: Intent) = googleSignInLauncher.launch(intent)
    fun launchFirebaseGoogleSignIn(intent: Intent) = firebaseGoogleSignInLauncher.launch(intent)
    fun launchCoverPicker(intent: Intent) = coverPickerLauncher.launch(intent)
    fun launchProfileAvatar(intent: Intent) = profileAvatarLauncher.launch(intent)
    fun launchGalleryPicker() = galleryImagesLauncher.launch(arrayOf("image/*"))
    fun launchDownloadFolderPicker() {
        // Ask Android's document picker to start inside Download/MARCO RX6
        // instead of showing the storage root. The user can still choose another folder.
        val initialFolder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Download/MARCO RX6"
                )
            }.getOrNull()
        } else {
            null
        }
        downloadFolderPickerLauncher.launch(initialFolder)
    }
}
