package com.marcorx6.library.ui

import android.app.Activity
import android.app.Dialog
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
    private val translate: (String) -> String
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
                val latestVersion = update.optString("versionName").trim()
                    .removePrefix("v").removePrefix("V")
                if (latestVersion.isBlank()) return@Thread

                val downloadPage = update.optString("downloadPageUrl").ifBlank { OFFICIAL_DOWNLOAD_PAGE }
                if (!downloadPage.startsWith(OFFICIAL_DOWNLOAD_PREFIX)) return@Thread

                // Only a valid API response counts as a completed check. Network, JSON,
                // or malformed-response failures remain immediately retryable.
                preferences.edit().putLong(LAST_CHECK_KEY, System.currentTimeMillis()).apply()

                if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) <= 0) {
                    return@Thread
                }
                val notes = update.optString("releaseNotes").trim().take(MAX_NOTES_LENGTH)

                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    val variant = preferredVariant()
                    showFullScreenUpdate(latestVersion, variant, notes)
                }
            } catch (_: Exception) {
                // Keep automatic checks silent when offline or when the website is unavailable.
            } finally {
                connection?.disconnect()
            }
        }, "marco-update-check").start()
    }

    /**
     * Full-screen update experience. DownloadManager and the existing install
     * hand-off remain unchanged; this only replaces the old compact alert.
     */
    private fun showFullScreenUpdate(version: String, variant: String, notes: String) {
        val preferences = activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE)
        val dark = preferences.getBoolean("dark_mode", true)
        val accent = AccentPalette.color(preferences.getString(AccentPalette.PREFERENCE_KEY, AccentPalette.DEFAULT_ID) ?: AccentPalette.DEFAULT_ID)
        val background = if (dark) android.graphics.Color.rgb(9, 11, 15) else android.graphics.Color.rgb(246, 248, 251)
        val surface = if (dark) android.graphics.Color.rgb(22, 25, 31) else android.graphics.Color.WHITE
        val foreground = if (dark) android.graphics.Color.rgb(239, 242, 247) else android.graphics.Color.rgb(27, 31, 38)
        val muted = if (dark) android.graphics.Color.rgb(165, 173, 185) else android.graphics.Color.rgb(103, 111, 123)
        val stroke = if (dark) android.graphics.Color.rgb(57, 63, 73) else android.graphics.Color.rgb(222, 226, 233)
        val dialog = Dialog(activity)
        val root = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(22))
            setBackgroundColor(background)
            layoutDirection = if ((preferences.getString("language", "ar") ?: "ar") == "ar")
                android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR
        }
        val scroll = android.widget.ScrollView(activity).apply {
            isFillViewport = true
            clipToPadding = false
            addView(root, android.widget.ScrollView.LayoutParams(-1, -2))
        }
        fun label(value: String, size: Float, color: Int, bold: Boolean = false): android.widget.TextView =
            android.widget.TextView(activity).apply {
                text = value
                textSize = size
                setTextColor(color)
                if (bold) typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                includeFontPadding = true
            }
        fun panel(): android.widget.LinearLayout = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), stroke)
            }
        }
        root.addView(label("MARCO RX6", 13f, accent, true), android.widget.LinearLayout.LayoutParams(-1, -2))
        val heading = label(translate("يتوفر تحديث جديد"), 27f, foreground, true).apply {
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(heading)
        root.addView(label(translate("سيتم تنزيل النسخة المناسبة مباشرة داخل التطبيق"), 14f, muted), android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) })

        val versions = panel()
        val currentRow = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        currentRow.addView(label(translate("الإصدار الحالي"), 14f, muted), android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        currentRow.addView(label(BuildConfig.VERSION_NAME, 16f, foreground, true))
        versions.addView(currentRow)
        val separator = android.view.View(activity).apply { setBackgroundColor(stroke) }
        versions.addView(separator, android.widget.LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(13); bottomMargin = dp(13) })
        val newRow = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        newRow.addView(label(translate("الإصدار الجديد"), 14f, muted), android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        newRow.addView(label(version, 19f, accent, true))
        versions.addView(newRow)
        val variantText = label("${translate("نسخة الجهاز")}: ${variantLabel(variant)}", 13f, muted).apply { setPadding(0, dp(12), 0, 0) }
        versions.addView(variantText)
        root.addView(versions, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        if (notes.isNotBlank()) {
            val notesPanel = panel()
            notesPanel.addView(label(translate("التغييرات"), 16f, foreground, true))
            notesPanel.addView(label(notes, 14f, muted).apply { setPadding(0, dp(10), 0, 0); setLineSpacing(dp(3).toFloat(), 1f) })
            root.addView(notesPanel, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        }
        val spacer = android.view.View(activity).apply { minimumHeight = dp(8) }
        root.addView(spacer, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        fun actionButton(text: String, filled: Boolean, click: () -> Unit): android.widget.TextView =
            android.widget.TextView(activity).apply {
                this.text = text
                textSize = 15f
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setTextColor(if (filled) AccentPalette.foregroundForColor(accent) else foreground)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (filled) accent else surface)
                    cornerRadius = dp(15).toFloat()
                    if (!filled) setStroke(dp(1), stroke)
                }
                isClickable = true
                isFocusable = true
                setPadding(dp(16), dp(15), dp(16), dp(15))
                setOnClickListener { click() }
            }
        root.addView(actionButton(translate("تحديث الآن"), true) {
            dialog.dismiss()
            startApkDownload(variant, version)
        }, android.widget.LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(12) })
        root.addView(actionButton(translate("لاحقاً"), false) {
            dialog.dismiss()
        }, android.widget.LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })

        dialog.setContentView(scroll)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(background))
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        dialog.window?.statusBarColor = background
        dialog.window?.navigationBarColor = background
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            dialog.window?.decorView?.systemUiVisibility = if (dark) 0 else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

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
                            .edit().putString(PENDING_INSTALL_URI, uri.toString())
                            .putBoolean(INSTALL_PERMISSION_PROMPTED, false).apply()
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
        private const val INSTALL_PERMISSION_PROMPTED = "app_update_install_permission_prompted"
        private const val CHECK_INTERVAL_MS = 15L * 60L * 1000L
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
            if (activity is androidx.lifecycle.LifecycleOwner &&
                !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            ) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.packageManager.canRequestPackageInstalls()
            ) {
                if (preferences.getBoolean(INSTALL_PERMISSION_PROMPTED, false)) {
                    Toast.makeText(activity, translate("اسمح بتثبيت التطبيقات من هذا المصدر لإكمال التحديث"), Toast.LENGTH_LONG).show()
                    return
                }
                preferences.edit().putBoolean(INSTALL_PERMISSION_PROMPTED, true).apply()
                runCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}"))
                    )
                }.onFailure {
                    preferences.edit().putBoolean(INSTALL_PERMISSION_PROMPTED, false).apply()
                    Toast.makeText(activity, translate("تعذر فتح إعدادات تثبيت التطبيقات"), Toast.LENGTH_LONG).show()
                }
                return
            }
            val uri = Uri.parse(uriText)
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, APK_MIME)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
                preferences.edit().remove(PENDING_INSTALL_URI)
                    .putBoolean(INSTALL_PERMISSION_PROMPTED, false).apply()
            }.onFailure {
                Toast.makeText(activity, translate("تعذر فتح مثبت تحديث Android"), Toast.LENGTH_LONG).show()
            }
        }
    }
}
