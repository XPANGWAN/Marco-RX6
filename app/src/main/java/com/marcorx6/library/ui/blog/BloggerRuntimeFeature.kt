package com.marcorx6.library.ui.blog

import com.marcorx6.library.ui.*

import android.widget.EditText
import android.widget.ImageView
import androidx.activity.ComponentActivity
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.marcorx6.library.data.Game
import com.marcorx6.library.network.BloggerFeedClient
import com.marcorx6.library.data.GameLibraryStore
import java.util.concurrent.ExecutorService

/**
 * Owns Blogger access, synchronization and post composition.
 * Google account and UI state remain supplied through narrow callbacks.
 */
internal class BloggerRuntimeFeature(
    activity: ComponentActivity,
    services: BloggerServices,
    private val executor: ExecutorService,
    currentAccount: () -> GoogleSignInAccount?,
    currentBlogId: () -> String,
    setAdmin: (Boolean) -> Unit,
    isAdminSession: () -> Boolean,
    private val feedUrl: String = "https://marcorx6app.dpdns.org/api/games?alt=json&max-results=100",
    private val postToUi: (() -> Unit) -> Unit,
    refreshUi: () -> Unit,
    private val merge: (List<Game>) -> Pair<Int, Int>,
    setBlogId: (String) -> Unit,
    refreshAccess: () -> Unit,
    private val renderGames: () -> Unit,
    gameLibraryStore: GameLibraryStore,
    dp: (Int) -> Int,
    bg: () -> Int,
    isDarkMode: () -> Boolean,
    panel: () -> Int,
    white: () -> Int,
    gray: () -> Int,
    red: () -> Int,
    makeText: (String, Float, Int, Boolean) -> android.widget.TextView,
    makeInput: (String) -> android.widget.EditText,
    rounded: (Int, Int) -> android.graphics.drawable.GradientDrawable,
    accentForeground: () -> Int,
    executeIo: (() -> Unit) -> Unit,
    styleAlertDialog: (android.app.AlertDialog) -> Unit,
    applyUnifiedSkin: (android.view.View) -> Unit,
    setContentView: (android.view.View) -> Unit,
    onBack: () -> Unit,
    setCoverFields: (EditText, ImageView) -> Unit,
    pendingCoverLocalPath: () -> String,
    setPendingCoverLocalPath: (String) -> Unit,
    pendingCoverPublicUrl: () -> String,
    setPendingCoverPublicUrl: (String) -> Unit,
    coverUploadInProgress: () -> Boolean,
    loadCover: (ImageView, String, String) -> Unit,
    chooseCover: () -> Unit,
    chooseGalleryImages: ((List<android.net.Uri>) -> Unit) -> Unit,
    uploadGalleryImages: (List<android.net.Uri>, (List<String>, String?) -> Unit) -> Unit,
    deleteCoverFileIfUnused: (String) -> Unit,
    resetCoverUploadFailed: () -> Unit,
    saveGames: () -> Unit,
    translate: (String) -> String
) {
    private var syncing = false

    // Refresh the public Blogger feed when users return to MARCO, but avoid
    // repeatedly requesting the paginated feed during rapid screen/app switches.
    private val autoSyncPreferences = activity.getSharedPreferences(
        "blogger_sync_state",
        android.content.Context.MODE_PRIVATE
    )
    private val autoSyncIntervalMillis = 15L * 60L * 1000L

    val isSyncing: Boolean get() = syncing

    private val bloggerAccessFeature by lazy {
        BloggerAccessFeature(
            context = activity,
            services = services,
            executor = executor,
            currentAccount = currentAccount,
            currentBlogId = currentBlogId,
            setAdmin = setAdmin,
            postToUi = postToUi,
            refreshUi = refreshUi,
            translate = translate
        )
    }

    private val blogSyncFeature by lazy {
        BlogSyncFeature(
            context = activity,
            executor = executor,
            isSyncing = { syncing },
            setSyncing = { syncing = it },
            feedUrl = feedUrl,
            postToUi = postToUi,
            merge = merge,
            setBlogId = setBlogId,
            refreshAccess = refreshAccess,
            renderGames = renderGames,
            translate = translate
        )
    }

    private val bloggerPostFeature by lazy {
        BloggerPostFeature(
            activity = activity,
            services = services,
            gameLibraryStore = gameLibraryStore,
            isAdminSession = isAdminSession,
            bloggerBlogId = currentBlogId,
            dp = dp,
            isDarkMode = isDarkMode,
            bg = bg,
            panel = panel,
            white = white,
            gray = gray,
            red = red,
            makeText = makeText,
            makeInput = makeInput,
            rounded = rounded,
            accentForeground = accentForeground,
            executeIo = executeIo,
            postToUi = postToUi,
            styleAlertDialog = styleAlertDialog,
            applyUnifiedSkin = applyUnifiedSkin,
            setContentView = setContentView,
            onBack = onBack,
            setCoverFields = setCoverFields,
            pendingCoverLocalPath = pendingCoverLocalPath,
            setPendingCoverLocalPath = setPendingCoverLocalPath,
            pendingCoverPublicUrl = pendingCoverPublicUrl,
            setPendingCoverPublicUrl = setPendingCoverPublicUrl,
            coverUploadInProgress = coverUploadInProgress,
            loadCover = loadCover,
            chooseCover = chooseCover,
            chooseGalleryImages = chooseGalleryImages,
            uploadGalleryImages = uploadGalleryImages,
            deleteCoverFileIfUnused = deleteCoverFileIfUnused,
            resetCoverUploadFailed = resetCoverUploadFailed,
            saveGames = saveGames,
            renderGames = renderGames,
            translate = translate
        )
    }

    /**
     * Refreshes the public feed in the background and returns the newest cached game
     * record for an in-app recovery flow. The Blogger page itself is never displayed.
     */
    fun refreshGame(sourcePostUrl: String, onComplete: (Game?) -> Unit) {
        executor.execute {
            val refreshed = runCatching {
                BloggerFeedClient(feedUrl).fetch().games.firstOrNull { game ->
                    game.sourcePostUrl == sourcePostUrl || game.url == sourcePostUrl
                }
            }.getOrNull()
            postToUi {
                if (refreshed != null) {
                    merge(listOf(refreshed))
                    renderGames()
                }
                onComplete(refreshed)
            }
        }
    }

    fun refreshAccess() = bloggerAccessFeature.refresh()

    fun sync(showResult: Boolean) {
        if (!syncing) {
            autoSyncPreferences.edit()
                .putLong("last_sync_attempt", System.currentTimeMillis())
                .apply()
        }
        blogSyncFeature.sync(showResult)
    }

    /** Runs a quiet feed refresh only when the previous attempt is at least 15 minutes old. */
    fun syncIfStale() {
        if (syncing) return
        val lastAttempt = autoSyncPreferences.getLong("last_sync_attempt", 0L)
        val now = System.currentTimeMillis()
        if (lastAttempt == 0L || now - lastAttempt >= autoSyncIntervalMillis) {
            sync(showResult = false)
        }
    }
    fun showComposer(existing: Game? = null) = bloggerPostFeature.showComposer(existing)
    fun delete(game: Game) = bloggerPostFeature.delete(game)
}
