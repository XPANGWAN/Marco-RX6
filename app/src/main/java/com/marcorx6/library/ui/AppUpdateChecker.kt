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
 * Checks the official MARCO RX6 website for stable app updates.
 * Download selection remains on the website, which offers all three APK variants.
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

        preferences.edit().putLong(LAST_CHECK_KEY, System.currentTimeMillis()).apply()
        Thread({
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(UPDATE_API).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 12000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "MARCO-RX6-Android")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                if (connection.responseCode !in 200..299) return@Thread

                val update = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val latestVersion = update.optString("versionName").trim()
                    .removePrefix("v").removePrefix("V")
                if (latestVersion.isBlank() || compareVersions(latestVersion, BuildConfig.VERSION_NAME) <= 0) {
                    return@Thread
                }

                val downloadPage = update.optString("downloadPageUrl").ifBlank { OFFICIAL_DOWNLOAD_PAGE }
                if (!downloadPage.startsWith(OFFICIAL_DOWNLOAD_PREFIX)) return@Thread
                val notes = update.optString("releaseNotes").trim().take(MAX_NOTES_LENGTH)

                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    val message = buildString {
                        append(translate("الإصدار الحالي")).append(": ").append(BuildConfig.VERSION_NAME)
                        append("\n").append(translate("الإصدار الجديد")).append(": ").append(latestVersion)
                        if (notes.isNotBlank()) {
                            append("\n\n").append(translate("التغييرات")).append(":\n").append(notes)
                        }
                        append("\n\n").append(translate("اختر نسخة APK المناسبة لجهازك من الموقع الرسمي"))
                    }
                    AlertDialog.Builder(activity)
                        .setTitle(translate("يتوفر تحديث جديد"))
                        .setMessage(message)
                        .setNegativeButton(translate("لاحقاً"), null)
                        .setPositiveButton(translate("تحميل التحديث")) { _, _ ->
                            openUrl(downloadPage)
                        }
                        .show().also(styleDialog)
                }
            } catch (_: Exception) {
                // Keep automatic checks silent when offline or when the website is unavailable.
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
        val a = Regex("""\\d+""").findAll(first).mapNotNull { it.value.toIntOrNull() }.toList()
        val b = Regex("""\\d+""").findAll(second).mapNotNull { it.value.toIntOrNull() }.toList()
        if (a.isEmpty() || b.isEmpty()) return 0
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    companion object {
        private const val UPDATE_API = "https://marcorx6app.dpdns.org/api/update"
        private const val OFFICIAL_DOWNLOAD_PAGE = "https://marcorx6app.dpdns.org/#download"
        private const val OFFICIAL_DOWNLOAD_PREFIX = "https://marcorx6app.dpdns.org/"
        private const val PREFERENCES = "app_settings"
        private const val LAST_CHECK_KEY = "last_automatic_update_check"
        private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private const val MAX_NOTES_LENGTH = 2400
    }
}
