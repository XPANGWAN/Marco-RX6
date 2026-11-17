package com.marcorx6.library.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.marcorx6.library.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Performs a quiet, rate-limited release check. When a newer stable release exists,
 * the user can open its APK download in the browser and install it through Android.
 */
internal class AppUpdateChecker(
    private val activity: Activity,
    private val translate: (String) -> String,
    private val styleDialog: (AlertDialog) -> Unit = {}
) {
    fun checkAutomatically() {
        val preferences = activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE)
        val lastCheck = preferences.getLong(LAST_CHECK_KEY, 0L)
        if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) return

        // Record the attempt first to avoid repeated network requests during rapid resumes.
        preferences.edit().putLong(LAST_CHECK_KEY, System.currentTimeMillis()).apply()
        Thread({
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 12000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "MARCO-RX6-Android")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                if (connection.responseCode !in 200..299) return@Thread
                val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) return@Thread

                val latestVersion = release.optString("tag_name").trim()
                    .removePrefix("v").removePrefix("V")
                if (latestVersion.isBlank() || compareVersions(latestVersion, BuildConfig.VERSION_NAME) <= 0) {
                    return@Thread
                }

                val releasePage = release.optString("html_url")
                    .takeIf { it.startsWith("https://github.com/") }
                    ?: OFFICIAL_WEBSITE
                val assets = release.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) {
                    for (index in 0 until assets.length()) {
                        val asset = assets.optJSONObject(index) ?: continue
                        val name = asset.optString("name")
                        val url = asset.optString("browser_download_url")
                        if (name.endsWith(".apk", ignoreCase = true) &&
                            url.startsWith("https://github.com/")) {
                            apkUrl = url
                            break
                        }
                    }
                }
                val notes = release.optString("body").trim().take(MAX_NOTES_LENGTH)
                val downloadUrl = apkUrl ?: releasePage

                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    val message = buildString {
                        append(translate("الإصدار الحالي")).append(": ").append(BuildConfig.VERSION_NAME)
                        append("\n").append(translate("الإصدار الجديد")).append(": ").append(latestVersion)
                        if (notes.isNotBlank()) {
                            append("\n\n").append(translate("التغييرات")).append(":\n").append(notes)
                        }
                        if (apkUrl == null) {
                            append("\n\n").append(translate("سيتم فتح صفحة الإصدار لتنزيل ملف APK"))
                        }
                    }
                    AlertDialog.Builder(activity)
                        .setTitle(translate("يتوفر تحديث جديد"))
                        .setMessage(message)
                        .setNegativeButton(translate("لاحقاً"), null)
                        .setPositiveButton(translate("تحميل التحديث")) { _, _ ->
                            openUrl(downloadUrl)
                        }
                        .show().also(styleDialog)
                }
            } catch (_: Exception) {
                // Automatic checks are deliberately silent when offline or unavailable.
            } finally {
                connection?.disconnect()
            }
        }, "marco-update-check").start()
    }

    private fun openUrl(url: String) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(activity, translate("تعذر فتح رابط التحديث"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun compareVersions(first: String, second: String): Int {
        val a = Regex("""\d+""").findAll(first).mapNotNull { it.value.toIntOrNull() }.toList()
        val b = Regex("""\d+""").findAll(second).mapNotNull { it.value.toIntOrNull() }.toList()
        if (a.isEmpty() || b.isEmpty()) return 0
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    companion object {
        private const val RELEASE_API =
            "https://api.github.com/repos/XPANGWAN/Marco-RX6/releases/latest"
        private const val OFFICIAL_WEBSITE = "https://marcorx6app.dpdns.org/"
        private const val PREFERENCES = "app_settings"
        private const val LAST_CHECK_KEY = "last_automatic_update_check"
        private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private const val MAX_NOTES_LENGTH = 2400
    }
}
