package com.marcorx6.library.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.marcorx6.library.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the official MARCO RX6 website for stable app updates and downloads
 * the matching APK directly through Android DownloadManager.
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
                preferences.edit().putLong(LAST_CHECK_KEY, System.currentTimeMillis()).apply()
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
                    val variant = preferredVariant()
                    val message = buildString {
                        append(translate("الإصدار الحالي")).append(": ").append(BuildConfig.VERSION_NAME)
                        append("\n").append(translate("الإصدار الجديد")).append(": ").append(latestVersion)
                        append("\n").append(translate("نسخة الجهاز")).append(": ").append(variantLabel(variant))
                        if (notes.isNotBlank()) {
                            append("\n\n").append(translate("التغييرات")).append(":\n").append(notes)
                        }
                        append("\n\n").append(translate("سيتم تنزيل النسخة المناسبة مباشرة داخل التطبيق"))
                    }
                    AlertDialog.Builder(activity)
                        .setTitle(translate("يتوفر تحديث جديد"))
                        .setMessage(message)
                        .setNegativeButton(translate("لاحقاً"), null)
                        .setPositiveButton(translate("تحميل التحديث")) { _, _ ->
                            startApkDownload(variant, latestVersion)
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

    private fun startApkDownload(variant: String, version: String) {
        runCatching {
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val fileName = "MARCO-RX6-${version.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk"
            val request = DownloadManager.Request(Uri.parse("$OFFICIAL_DOWNLOAD_PREFIX" + "download/$variant"))
                .setTitle("MARCO RX6 $version")
                .setDescription(translate("جارٍ تنزيل تحديث التطبيق"))
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName)
            val id = manager.enqueue(request)
            activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE)
                .edit().putLong(PENDING_DOWNLOAD_ID, id).apply()
            monitorDownload(manager, id, version)
        }.onFailure {
            Toast.makeText(activity, translate("تعذر بدء تنزيل التحديث"), Toast.LENGTH_LONG).show()
        }
    }

    private fun monitorDownload(manager: DownloadManager, id: Long, version: String) {
        val progress = ProgressDialog(activity).apply {
            setTitle(translate("تنزيل تحديث MARCO RX6"))
            setMessage(translate("جارٍ الاتصال بخادم التنزيل..."))
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setCancelable(false)
            setButton(ProgressDialog.BUTTON_NEGATIVE, translate("إلغاء")) { _, _ ->
                manager.remove(id)
                activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE)
                    .edit().remove(PENDING_DOWNLOAD_ID).apply()
            }
            show()
        }
        val handler = Handler(Looper.getMainLooper())
        lateinit var poll: Runnable
        poll = Runnable {
            if (activity.isFinishing || activity.isDestroyed) {
                handler.removeCallbacks(poll)
                return@Runnable
            }
            val query = DownloadManager.Query().setFilterById(id)
            val cursor = manager.query(query)
            if (cursor == null || !cursor.moveToFirst()) {
                cursor?.close()
                progress.dismiss()
                Toast.makeText(activity, translate("تعذر العثور على ملف التحديث"), Toast.LENGTH_LONG).show()
                return@Runnable
            }
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            cursor.close()

            if (total > 0L) {
                val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                progress.progress = percent
                progress.setMessage("$percent%  •  ${formatBytes(downloaded)} / ${formatBytes(total)}")
            } else {
                progress.setMessage("${translate("جارٍ التنزيل")}  •  ${formatBytes(downloaded)}")
            }

            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    progress.progress = 100
                    progress.dismiss()
                    activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE)
                        .edit().remove(PENDING_DOWNLOAD_ID).apply()
                    val uri = manager.getUriForDownloadedFile(id)
                    if (uri == null) {
                        Toast.makeText(activity, translate("اكتمل التنزيل لكن تعذر فتح ملف APK"), Toast.LENGTH_LONG).show()
                    } else {
                        activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE)
                            .edit().putString(PENDING_INSTALL_URI, uri.toString()).apply()
                        installPendingUpdate(activity, translate)
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    progress.dismiss()
                    activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE)
                        .edit().remove(PENDING_DOWNLOAD_ID).apply()
                    Toast.makeText(activity, "${translate("فشل تنزيل التحديث")} ($reason)", Toast.LENGTH_LONG).show()
                }
                else -> handler.postDelayed(poll, POLL_INTERVAL_MS)
            }
        }
        handler.post(poll)
    }

    private fun preferredVariant(): String {
        val abis = Build.SUPPORTED_ABIS.map { it.lowercase() }
        return when {
            abis.any { it == "arm64-v8a" } -> "arm64"
            abis.any { it == "armeabi-v7a" || it == "armeabi" } -> "arm32"
            else -> "universal"
        }
    }

    private fun variantLabel(variant: String): String = when (variant) {
        "arm64" -> "ARM64"
        "arm32" -> "ARM32"
        else -> "Universal"
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024L) return "$value B"
        val kb = value / 1024.0
        if (kb < 1024.0) return String.format(java.util.Locale.ROOT, "%.1f KB", kb)
        return String.format(java.util.Locale.ROOT, "%.1f MB", kb / 1024.0)
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
        private const val UPDATE_API = "https://marcorx6app.dpdns.org/api/update"
        private const val OFFICIAL_DOWNLOAD_PAGE = "https://marcorx6app.dpdns.org/#download"
        private const val OFFICIAL_DOWNLOAD_PREFIX = "https://marcorx6app.dpdns.org/"
        private const val PREFERENCES = "app_settings"
        private const val LAST_CHECK_KEY = "last_automatic_update_check"
        private const val PENDING_DOWNLOAD_ID = "pending_app_update_download_id"
        private const val PENDING_INSTALL_URI = "pending_app_update_install_uri"
        private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private const val POLL_INTERVAL_MS = 500L
        private const val MAX_NOTES_LENGTH = 2400
        private const val APK_MIME = "application/vnd.android.package-archive"

        /** Called when the app returns to foreground after an update download. */
        fun resumePendingUpdate(activity: Activity) {
            val preferences = activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE)
            val id = preferences.getLong(PENDING_DOWNLOAD_ID, -1L)
            if (id <= 0L) {
                installPendingUpdate(activity) { it }
                return
            }
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return
            cursor.use {
                if (!it.moveToFirst()) return
                when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        manager.getUriForDownloadedFile(id)?.let { uri ->
                            preferences.edit().remove(PENDING_DOWNLOAD_ID)
                                .putString(PENDING_INSTALL_URI, uri.toString()).apply()
                            installPendingUpdate(activity) { it }
                        }
                    }
                    DownloadManager.STATUS_FAILED -> preferences.edit().remove(PENDING_DOWNLOAD_ID).apply()
                }
            }
        }

        fun installPendingUpdate(activity: Activity, translate: (String) -> String = { it }) {
            val preferences = activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE)
            val uriText = preferences.getString(PENDING_INSTALL_URI, null) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.packageManager.canRequestPackageInstalls()
            ) {
                runCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}"))
                    )
                }.onFailure {
                    Toast.makeText(activity, translate("اسمح بتثبيت التطبيقات من هذا المصدر لإكمال التحديث"), Toast.LENGTH_LONG).show()
                }
                return
            }
            val uri = Uri.parse(uriText)
            preferences.edit().remove(PENDING_INSTALL_URI).apply()
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, APK_MIME)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            }.onFailure {
                Toast.makeText(activity, translate("تعذر فتح مثبت تحديث Android"), Toast.LENGTH_LONG).show()
            }
        }
    }
}
