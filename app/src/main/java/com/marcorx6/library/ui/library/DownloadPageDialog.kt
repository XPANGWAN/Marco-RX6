package com.marcorx6.library.ui

import android.app.Activity
import android.app.Dialog
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.text.InputType
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.marcorx6.library.network.SecureUrlPolicy
import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A small in-app browser for download pages that are not direct files.
 * It lets the user complete normal site steps and detects likely file links
 * without bypassing captchas, sign-in, timers, or other site protections.
 */
internal class DownloadPageDialog(
    private val activity: Activity,
    private val translate: (String) -> String
) {
    fun show(
        startUrl: String,
        gameTitle: String,
        onDownload: (String, String, String, String, String, String) -> Unit,
        onOpenDownloads: () -> Unit = {}
    ) {
        val safeStartUrl = SecureUrlPolicy.toHttpsUrl(startUrl)
        if (safeStartUrl == null) {
            Toast.makeText(activity, translate("رابط صفحة التحميل غير آمن أو غير صالح"), Toast.LENGTH_LONG).show()
            return
        }

        fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
        // Use the exact shared appearance store used by the app-wide theme switch.
        // Reading app_settings here silently defaulted this browser to dark mode.
        val appPreferences = AppearancePreferencesStore.appearance(activity)
        val dark = AppearancePreferencesStore.isDarkMode(appPreferences)
        val selectedAccentId = appPreferences.getString(AccentPalette.PREFERENCE_KEY, AccentPalette.DEFAULT_ID)
            ?: AccentPalette.DEFAULT_ID
        val primary = AccentPalette.color(selectedAccentId)
        val accentText = AccentPalette.readableAccentText(selectedAccentId, dark)
        val background = AccentPalette.surface(
            if (dark) Color.rgb(8, 9, 12) else Color.rgb(242, 244, 247),
            primary,
            if (dark) 0.20f else 0.10f
        )
        val surface = AccentPalette.surface(
            if (dark) Color.rgb(25, 28, 34) else Color.WHITE,
            primary,
            if (dark) 0.30f else 0.16f
        )
        val border = if (dark) Color.rgb(70, 77, 89) else Color.rgb(205, 211, 220)
        val heading = if (dark) Color.WHITE else Color.rgb(25, 29, 36)
        val muted = if (dark) Color.rgb(178, 184, 194) else Color.rgb(91, 98, 110)

        val dialog = Dialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            setPadding(0, 0, 0, 0)
            clipToPadding = false
        }
        val webView = WebView(activity)
        val browserHistory = mutableListOf<Pair<String, String>>()
        val browserBookmarks = mutableListOf<Pair<String, String>>()
        val browserTabs = mutableListOf<Pair<String, String>>()
        val browserTabStates = mutableListOf<android.os.Bundle?>()
        var activeTabIndex = 0
        fun saveActiveTabState() {
            if (activeTabIndex !in browserTabStates.indices) return
            val state = android.os.Bundle()
            if (runCatching { webView.saveState(state) }.isSuccess) {
                browserTabStates[activeTabIndex] = state
            }
        }
        fun restoreBrowserTab(index: Int) {
            if (index !in browserTabs.indices) return
            activeTabIndex = index
            val savedState = browserTabStates.getOrNull(index)
            val restored = savedState != null && runCatching {
                webView.restoreState(savedState) != null
            }.getOrDefault(false)
            if (!restored) webView.loadUrl(browserTabs[index].second)
        }
        runCatching {
            val saved = JSONArray(appPreferences.getString("download_browser_history", "[]"))
            for (i in 0 until saved.length()) {
                val item = saved.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (url.startsWith("https://")) browserHistory.add(item.optString("title") to url)
            }
        }
        runCatching {
            val saved = JSONArray(appPreferences.getString("download_browser_bookmarks", "[]"))
            for (i in 0 until saved.length()) {
                val item = saved.optJSONObject(i) ?: continue
                val url = SecureUrlPolicy.toHttpsUrl(item.optString("url")) ?: continue
                browserBookmarks.add(item.optString("title").ifBlank { Uri.parse(url).host.orEmpty() } to url)
            }
        }
        browserTabs.add(translate("التبويب 1") to safeStartUrl)
        browserTabStates.add(null)

        fun persistBrowserHistory() {
            val json = JSONArray()
            browserHistory.take(60).forEach { (title, url) ->
                json.put(org.json.JSONObject().put("title", title).put("url", url))
            }
            appPreferences.edit().putString("download_browser_history", json.toString()).apply()
        }

        fun persistBookmarks() {
            val json = JSONArray()
            browserBookmarks.take(100).forEach { (title, url) ->
                json.put(org.json.JSONObject().put("title", title).put("url", url))
            }
            appPreferences.edit().putString("download_browser_bookmarks", json.toString()).apply()
        }

        fun toggleCurrentBookmark() {
            val url = SecureUrlPolicy.toHttpsUrl(webView.url ?: safeStartUrl) ?: return
            val existing = browserBookmarks.indexOfFirst { it.second == url }
            if (existing >= 0) {
                browserBookmarks.removeAt(existing)
                Toast.makeText(activity, translate("تمت إزالة الموقع من المفضلة"), Toast.LENGTH_SHORT).show()
            } else {
                val title = webView.title.orEmpty().ifBlank { Uri.parse(url).host.orEmpty() }
                browserBookmarks.add(0, title to url)
                Toast.makeText(activity, translate("تم حفظ الموقع في المفضلة"), Toast.LENGTH_SHORT).show()
            }
            persistBookmarks()
        }

        fun openBrowserBookmarks() {
            if (browserBookmarks.isEmpty()) {
                Toast.makeText(activity, translate("لا توجد مواقع محفوظة في المفضلة"), Toast.LENGTH_SHORT).show()
                return
            }
            val labels = browserBookmarks.map { (title, url) ->
                (title.ifBlank { Uri.parse(url).host.orEmpty() }) + "\n" + url
            }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle(translate("المفضلة"))
                .setItems(labels) { _, which ->
                    browserBookmarks.getOrNull(which)?.second?.let { webView.loadUrl(it) }
                }
                .setNegativeButton(translate("إغلاق"), null)
                .setNeutralButton(translate("مسح المفضلة")) { _, _ ->
                    browserBookmarks.clear()
                    persistBookmarks()
                    Toast.makeText(activity, translate("تم مسح المواقع المحفوظة"), Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        fun clearBrowsingData() {
            AlertDialog.Builder(activity)
                .setTitle(translate("الخصوصية"))
                .setMessage(translate("سيتم مسح سجل التصفح وملفات تعريف الارتباط وبيانات المواقع المخزنة. لن تُحذف الملفات التي نزّلتها."))
                .setNegativeButton(translate("إلغاء"), null)
                .setPositiveButton(translate("مسح البيانات")) { _, _ ->
                    browserHistory.clear()
                    persistBrowserHistory()
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    webView.clearCache(true)
                    webView.clearFormData()
                    Toast.makeText(activity, translate("تم مسح بيانات التصفح"), Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        fun rememberPage(url: String, title: String) {
            if (!url.startsWith("https://")) return
            browserHistory.removeAll { it.second == url }
            browserHistory.add(0, title.ifBlank { Uri.parse(url).host.orEmpty() } to url)
            while (browserHistory.size > 60) browserHistory.removeAt(browserHistory.lastIndex)
            persistBrowserHistory()
            if (activeTabIndex in browserTabs.indices) {
                browserTabs[activeTabIndex] = (title.ifBlank { browserTabs[activeTabIndex].first }) to url
            }
        }

        fun openBrowserHistory() {
            if (browserHistory.isEmpty()) {
                Toast.makeText(activity, translate("لا يوجد سجل تصفح بعد"), Toast.LENGTH_SHORT).show()
                return
            }
            val labels = browserHistory.map { (title, url) ->
                (title.ifBlank { Uri.parse(url).host.orEmpty() }) + "\n" + url
            }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle(translate("سجل التصفح"))
                .setItems(labels) { _, which ->
                    browserHistory.getOrNull(which)?.second?.let { webView.loadUrl(it) }
                }
                .setNegativeButton(translate("إغلاق"), null)
                .setNeutralButton(translate("مسح السجل")) { _, _ ->
                    browserHistory.clear()
                    persistBrowserHistory()
                    Toast.makeText(activity, translate("تم مسح سجل التصفح"), Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        fun openBrowserTabs() {
            val labels = browserTabs.mapIndexed { index, tab ->
                (if (index == activeTabIndex) "● " else "") +
                    (tab.first.ifBlank { translate("تبويب") + " " + (index + 1) }) +
                    "\n" + tab.second
            }.toMutableList()
            labels.add(translate("+ تبويب جديد"))
            AlertDialog.Builder(activity)
                .setTitle(translate("التبويبات المفتوحة") + " (" + browserTabs.size + "/8)")
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which == browserTabs.size) {
                        if (browserTabs.size >= 8) {
                            Toast.makeText(activity, translate("الحد الأقصى 8 تبويبات"), Toast.LENGTH_SHORT).show()
                        } else {
                            val input = EditText(activity).apply {
                                hint = translate("أدخل رابط الموقع")
                                setSingleLine(true)
                                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                            }
                            AlertDialog.Builder(activity)
                                .setTitle(translate("فتح تبويب جديد"))
                                .setView(input)
                                .setNegativeButton(translate("إلغاء"), null)
                                .setPositiveButton(translate("فتح")) { _, _ ->
                                    val raw = input.text.toString().trim()
                                    val candidate = if (raw.contains("://")) raw else "https://$raw"
                                    val safe = SecureUrlPolicy.toHttpsUrl(candidate)
                                    if (safe == null) {
                                        Toast.makeText(activity, translate("الرابط غير صالح أو غير آمن"), Toast.LENGTH_SHORT).show()
                                    } else {
                                        saveActiveTabState()
                                        browserTabs.add(translate("تبويب") + " " + (browserTabs.size + 1) to safe)
                                        browserTabStates.add(null)
                                        restoreBrowserTab(browserTabs.lastIndex)
                                    }
                                }
                                .show()
                        }
                    } else if (which in browserTabs.indices && which != activeTabIndex) {
                        saveActiveTabState()
                        restoreBrowserTab(which)
                    }
                }
                .setNegativeButton(translate("إغلاق"), null)
                .setNeutralButton(translate("إغلاق التبويب الحالي")) { _, _ ->
                    if (browserTabs.size > 1) {
                        webView.stopLoading()
                        browserTabs.removeAt(activeTabIndex)
                        browserTabStates.removeAt(activeTabIndex)
                        restoreBrowserTab((activeTabIndex - 1).coerceAtLeast(0))
                    } else {
                        Toast.makeText(activity, translate("يجب إبقاء تبويب واحد على الأقل"), Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
        fun toolbarAction(label: String, onClick: () -> Unit, iconRes: Int = 0): TextView =
            TextView(activity).apply {
                text = label
                if (iconRes != 0) {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
                    compoundDrawablePadding = dp(5)
                }
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(accentText)
                minWidth = dp(44)
                minHeight = dp(44)
                setPadding(dp(5), dp(5), dp(5), dp(5))
                setBackground(GradientDrawable().apply {
                    setColor(surface)
                    cornerRadius = dp(13).toFloat()
                    setStroke(dp(1), border)
                })
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    compoundDrawableTintList = android.content.res.ColorStateList.valueOf(accentText)
                    foreground = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(
                            Color.argb(if (dark) 70 else 45, Color.red(primary), Color.green(primary), Color.blue(primary))
                        ), null, android.graphics.drawable.ColorDrawable(Color.WHITE)
                    )
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }

        // Minimal browser header: address/search field and one ad-settings icon.
        val addressRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        val addressInput = EditText(activity).apply {
            setSingleLine(true)
            textSize = 13f
            setTextColor(heading)
            setHintTextColor(muted)
            hint = translate("ابحث أو أدخل عنوان الموقع")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(14), 0, dp(12), 0)
            setBackground(GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), border)
            })
        }
        addressRow.addView(addressInput, LinearLayout.LayoutParams(0, dp(46), 1f))
        addressInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val entered = addressInput.text.toString().trim()
                if (entered.isBlank()) {
                    addressInput.error = translate("أدخل رابطاً أو عبارة للبحث")
                    return@setOnEditorActionListener true
                }
                val isUrl = entered.contains("://") || android.util.Patterns.WEB_URL.matcher(entered).matches()
                val destination = if (isUrl) {
                    SecureUrlPolicy.toHttpsUrl(if (entered.contains("://")) entered else "https://$entered")
                } else {
                    Uri.Builder().scheme("https").authority("www.google.com")
                        .appendPath("search").appendQueryParameter("q", entered).build().toString()
                }
                if (destination != null) {
                    webView.loadUrl(destination)
                    (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                        ?.hideSoftInputFromWindow(addressInput.windowToken, 0)
                } else addressInput.error = translate("رابط الصفحة غير صالح أو غير آمن")
                true
            } else false
        }



        root.addView(addressRow, LinearLayout.LayoutParams(-1, -2))

        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(primary)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(primary)
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)).apply {
            topMargin = dp(8)
            bottomMargin = dp(6)
        })

        val status = TextView(activity).apply {
            text = translate("جارٍ فتح صفحة التحميل…")
            textSize = 10f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(muted)
            setPadding(dp(8), dp(2), dp(8), dp(3))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        val blockedStats = TextView(activity).apply {
            text = translate("الإعلانات والمتتبعات المحجوبة: 0")
            textSize = 10f
            setTextColor(accentText)
            setPadding(dp(3), 0, dp(3), dp(6))
        }

        val browserFrame = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            this.background = android.graphics.drawable.ColorDrawable(surface)
            clipToOutline = false
        }
        browserFrame.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        val swipeRefresh = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(activity).apply {
            setColorSchemeColors(primary)
            setProgressBackgroundColorSchemeColor(surface)
            // The direct child is browserFrame (a LinearLayout), not the WebView.
            // Delegate the scroll-up check to the actual page so ordinary vertical
            // gestures remain WebView scrolling; pull-to-refresh is available only
            // when the page itself is already at its top edge.
            setOnChildScrollUpCallback { _, _ -> webView.canScrollVertically(-1) }
            setOnRefreshListener { webView.reload() }
        }
        swipeRefresh.addView(browserFrame, android.view.ViewGroup.LayoutParams(-1, -1))
        root.addView(swipeRefresh, LinearLayout.LayoutParams(-1, 0, 1f))

        val candidateTitle = TextView(activity).apply {
            text = translate("روابط ملفات محتملة")
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(heading)
            setPadding(dp(3), dp(10), dp(3), dp(5))
            visibility = View.GONE
        }
        root.addView(candidateTitle, LinearLayout.LayoutParams(-1, -2))
        val candidates = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val candidateScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
            addView(candidates, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(candidateScroll, LinearLayout.LayoutParams(-1, dp(88)))

        fun confirmDownload(
            url: String,
            label: String,
            mimeType: String = "",
            contentDisposition: String = ""
        ) {
            val safeUrl = SecureUrlPolicy.toHttpsUrl(url) ?: return
            DownloadConfirmationPanel(activity, translate, dark).show(
                url = safeUrl,
                suggestedName = android.webkit.URLUtil.guessFileName(safeUrl, contentDisposition, mimeType)
                    .ifBlank { label.ifBlank { gameTitle } },
                mimeType = mimeType,
                onConfirm = { fileName ->
                    val safeName = fileName.trim()
                        .replace(Regex("""[\\\\/:*?"<>|]"""), "_")
                        .take(180)
                        .ifBlank { android.webkit.URLUtil.guessFileName(safeUrl, null, mimeType) }
                    val disposition = "attachment; filename=\"$safeName\""
                    onDownload(
                        safeUrl,
                        safeName.ifBlank { gameTitle },
                        webView.settings.userAgentString.orEmpty(),
                        disposition,
                        mimeType,
                        webView.url.orEmpty()
                    )
                }
            )
        }

        fun isDirectFileUrl(url: String): Boolean {
            val uri = try { Uri.parse(url) } catch (_: Exception) { return false }
            val path = uri.path.orEmpty().lowercase()
            val knownExtension = Regex("""\.(zip|7z|rar|iso|pkg|bin|cue|chd|img|pbp|cso|nsp|xci|apk|pdf|mp4|m4v|mkv|webm|mov|avi|flv|3gp|mp3|m4a|aac|wav|flac|ogg)$""", RegexOption.IGNORE_CASE).containsMatchIn(path)
            val downloadFlag = listOf("download", "attachment", "file", "export").any { key ->
                uri.getQueryParameter(key)?.let { value ->
                    value.equals("1", true) || value.equals("true", true) ||
                        value.equals("yes", true) || value.equals("download", true)
                } == true
            }
            val downloadPath = Regex("""(?:^|/)(?:download|attachment|export)(?:/|$)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(path)
            val host = uri.host.orEmpty().lowercase()
            // MediaFire share pages may contain a filename extension in their path.
            // Keep the main/www hosts in the in-app browser; only CDN download hosts
            // should be treated as direct file URLs.
            if (host == "mediafire.com" || host == "www.mediafire.com") return false
            val providerLanding =
                (host == "drive.google.com" || host == "docs.google.com") &&
                    (path.contains("/file/d/") || (path.endsWith("/uc") && uri.getQueryParameter("id") != null))
            return knownExtension || downloadFlag || downloadPath || providerLanding
        }

        val candidateUrls = mutableSetOf<String>()
        fun addCandidate(label: String, url: String) {
            val safeUrl = SecureUrlPolicy.toHttpsUrl(url) ?: return
            if (!candidateUrls.add(safeUrl) || candidates.childCount >= 8) return
            candidates.addView(TextView(activity).apply {
                text = "  " + label.take(70).ifBlank { translate("فتح رابط الملف المحتمل") }
                setCompoundDrawablesRelativeWithIntrinsicBounds(com.marcorx6.library.R.drawable.ic_action_external, 0, 0, 0)
                compoundDrawablePadding = dp(7)
                textSize = 12f
                setTextColor(heading)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                this.background = GradientDrawable().apply {
                    setColor(surface)
                    cornerRadius = dp(11).toFloat()
                    setStroke(dp(1), border)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    confirmDownload(safeUrl, label)
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
            candidateTitle.visibility = View.VISIBLE
            candidateScroll.visibility = View.VISIBLE
        }

        fun scanForFileLinks() {
            // A MutationObserver marks the page dirty whenever a script inserts a
            // download button, changes a link target, or reveals a delayed URL.
            // The native side polls this flag; no page script is allowed to click links.
            val script = """(function(){
                try {
                    if (!window.__marcoDownloadObserver && document.documentElement) {
                        window.__marcoDownloadDirty = true;
                        window.__marcoDownloadObserver = new MutationObserver(function(){
                            window.__marcoDownloadDirty = true;
                        });
                        window.__marcoDownloadObserver.observe(document.documentElement, {
                            subtree:true, childList:true, attributes:true,
                            attributeFilter:['href','action','src','data-href','data-url','data-download-url','data-file-url','download']
                        });
                    }
                    if (window.__marcoDownloadDirty === false) return '[]';
                    window.__marcoDownloadDirty = false;
                    var out=[], seen=Object.create(null);
                    var ext=/\.(zip|7z|rar|iso|pkg|bin|cue|chd|img|pbp|cso|nsp|xci|apk|pdf|mp4|m4v|mkv|webm|mov|avi|flv|3gp|mp3|m4a|aac|wav|flac|ogg)$/i;
                    var words=/(download|تحميل|تنزيل|رابط مباشر|direct|download file)/i;
                    var endpoint=/(?:^|\/)(download|downloads|attachment|file|export|raw)(?:\/|$)/i;
                    var selectors='a[href],a[data-href],a[data-url],[data-download-url],[data-file-url],form[action],video[src],video source[src],audio[src],audio source[src],button[data-href],button[data-url],button[data-download-url],[role="button"][data-href],[role="button"][data-url],input[formaction]';
                    function add(raw,label,force){
                        try {
                            var u=new URL(raw,location.href);
                            if(u.protocol!=='https:' || seen[u.href] || out.length>=20) return;
                            var p=u.pathname.toLowerCase(), q=u.search.toLowerCase();
                            var explicit=endpoint.test(p) || /[?&](download|attachment|file|export|dl)=(1|true|yes|download)(?:&|$)/i.test(q);
                            if(force || ext.test(p) || explicit){
                                seen[u.href]=1;
                                out.push({url:u.href,label:(label||'ملف مباشر محتمل').trim().slice(0,90)});
                            }
                        } catch(e) {}
                    }
                    document.querySelectorAll(selectors).forEach(function(e){
                        var h=e.getAttribute('href') || e.getAttribute('data-href') ||
                            e.getAttribute('data-url') || e.getAttribute('data-download-url') ||
                            e.getAttribute('data-file-url') || e.getAttribute('action') ||
                            e.getAttribute('formaction') || e.getAttribute('src') || '';
                        var t=(e.innerText||e.textContent||e.getAttribute('aria-label')||e.getAttribute('title')||'').trim();
                        var d=e.hasAttribute('download');
                        var tag=(e.tagName||'').toUpperCase();
                        var media=tag==='VIDEO'||tag==='AUDIO'||tag==='SOURCE';
                        var p=h.toLowerCase().split('?')[0];
                        if(media || d || words.test(t) || ext.test(p) || endpoint.test(p) ||
                            e.hasAttribute('data-download-url') || e.hasAttribute('data-file-url')) {
                            add(h,t,d||media||e.hasAttribute('data-download-url')||e.hasAttribute('data-file-url'));
                        }
                    });
                    return JSON.stringify(out);
                } catch(e) { return '[]'; }
            })()"""
            webView.evaluateJavascript(script) { raw ->
                try {
                    val decoded = JSONTokener(raw ?: "[]").nextValue()
                    val json = if (decoded is String) JSONArray(decoded) else decoded as? JSONArray ?: JSONArray()
                    for (index in 0 until json.length()) {
                        val item = json.optJSONObject(index) ?: continue
                        addCandidate(item.optString("label"), item.optString("url"))
                    }
                } catch (_: Exception) {
                    // A page may block script inspection; normal browsing still works.
                }
            }
        }

        // Re-scan only while the browser is visible. The observer keeps the
        // repeated scan cheap by returning immediately when the DOM is unchanged.
        val dynamicScanRunnable = object : Runnable {
            override fun run() {
                if (!dialog.isShowing || rendererGone) return
                scanForFileLinks()
                webView.postDelayed(this, 1_500L)
            }
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        val adPreferences = appPreferences
        var adBlockerEnabled = adPreferences.getBoolean("download_page_ad_blocker", true)
        val allowedHosts = adPreferences.getString("download_page_ad_allowlist", "").orEmpty()
            .split(',', ';').map { it.trim().lowercase() }.filter { it.isNotBlank() }.toMutableSet()
        val blockedSites = adPreferences.getString("download_page_blocked_sites", "").orEmpty()
            .split(',', ';').map { it.trim().lowercase() }.filter { it.isNotBlank() }.toMutableSet()
        lateinit var adToggle: android.widget.ImageButton
        fun showAdSettings() {
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(4))
            }
            val enabled = android.widget.CheckBox(activity).apply {
                text = translate("تفعيل حجب الإعلانات والمتتبعات")
                isChecked = adBlockerEnabled
                setTextColor(heading)
                buttonTintList = android.content.res.ColorStateList.valueOf(primary)
            }
            val allowlistInput = EditText(activity).apply {
                setText(allowedHosts.joinToString(", "))
                hint = translate("مواقع مسموح بها، افصل بينها بفاصلة")
                textSize = 13f
                setTextColor(heading)
                setHintTextColor(muted)
                inputType = InputType.TYPE_CLASS_TEXT
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackground(GradientDrawable().apply {
                    setColor(surface); cornerRadius = dp(12).toFloat(); setStroke(dp(1), border)
                })
            }
            content.addView(enabled)
            content.addView(TextView(activity).apply {
                text = translate("استثناء نطاقات المواقع من الحجب")
                textSize = 11f; setTextColor(muted)
                setPadding(0, dp(10), 0, dp(5))
            })
            content.addView(allowlistInput, LinearLayout.LayoutParams(-1, dp(48)))

            content.addView(TextView(activity).apply {
                text = translate("حظر المواقع يدوياً")
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(heading)
                setPadding(0, dp(16), 0, dp(6))
            })
            val blockedSiteInput = EditText(activity).apply {
                hint = translate("مثال: example.com")
                textSize = 13f
                setTextColor(heading)
                setHintTextColor(muted)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine(true)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackground(GradientDrawable().apply {
                    setColor(surface); cornerRadius = dp(12).toFloat(); setStroke(dp(1), border)
                })
            }
            content.addView(blockedSiteInput, LinearLayout.LayoutParams(-1, dp(46)))
            val workingBlockedSites = blockedSites.toMutableSet()
            val blockedList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            fun refreshBlockedList() {
                blockedList.removeAllViews()
                if (workingBlockedSites.isEmpty()) {
                    blockedList.addView(TextView(activity).apply {
                        text = translate("لا توجد مواقع محظورة يدوياً")
                        textSize = 11f
                        setTextColor(muted)
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                    })
                } else {
                    workingBlockedSites.sorted().forEach { host ->
                        val item = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(8), dp(3), dp(4), dp(3))
                            setBackground(GradientDrawable().apply {
                                setColor(surface)
                                cornerRadius = dp(10).toFloat()
                                setStroke(dp(1), border)
                            })
                        }
                        item.addView(TextView(activity).apply {
                            text = host
                            textSize = 12f
                            setTextColor(heading)
                            gravity = Gravity.CENTER_VERTICAL
                        }, LinearLayout.LayoutParams(0, dp(40), 1f))
                        item.addView(TextView(activity).apply {
                            text = translate("إزالة")
                            textSize = 11f
                            setTextColor(primary)
                            gravity = Gravity.CENTER
                            setPadding(dp(10), dp(8), dp(10), dp(8))
                            setOnClickListener {
                                workingBlockedSites.remove(host)
                                refreshBlockedList()
                            }
                        })
                        blockedList.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
                            bottomMargin = dp(5)
                        })
                    }
                }
            }
            val addBlockedSite = android.widget.Button(activity).apply {
                text = translate("إضافة إلى قائمة الحظر")
                setOnClickListener {
                    val raw = blockedSiteInput.text.toString().trim()
                        .removePrefix("https://").removePrefix("http://")
                        .substringBefore('/').substringBefore('?').substringBefore('#')
                        .substringBefore(':').trim().trim('.').lowercase()
                    val valid = raw.length <= 253 &&
                        raw.matches(Regex("(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}"))
                    if (!valid) {
                        blockedSiteInput.error = translate("أدخل اسم نطاق صحيحاً مثل example.com")
                    } else if (workingBlockedSites.size >= 100 && raw !in workingBlockedSites) {
                        Toast.makeText(activity, translate("وصلت إلى الحد الأقصى للمواقع المحظورة"), Toast.LENGTH_SHORT).show()
                    } else {
                        workingBlockedSites.add(raw)
                        blockedSiteInput.text.clear()
                        refreshBlockedList()
                    }
                }
            }
            content.addView(addBlockedSite, LinearLayout.LayoutParams(-1, dp(44)).apply {
                topMargin = dp(5)
            })
            content.addView(blockedList, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(8)
            })
            refreshBlockedList()
            AlertDialog.Builder(activity)
                .setCustomTitle(TextView(activity).apply {
                    text = translate("إعدادات الدرع والحماية")
                    textSize = 19f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(heading)
                    setPadding(dp(20), dp(12), dp(20), dp(4))
                })
                .setView(ScrollView(activity).apply { addView(content) })
                .setNegativeButton(translate("إلغاء"), null)
                .setPositiveButton(translate("حفظ")) { _, _ ->
                    adBlockerEnabled = enabled.isChecked
                    val cleaned = allowlistInput.text.toString().split(',', ';')
                        .map { it.trim().lowercase() }
                        .filter { it.matches(Regex("[a-z0-9.-]+")) && it.contains('.') }
                        .distinct().take(30)
                    appPreferences.edit()
                        .putBoolean("download_page_ad_blocker", adBlockerEnabled)
                        .putString("download_page_ad_allowlist", cleaned.joinToString(","))
                        .putString("download_page_blocked_sites", workingBlockedSites.sorted().joinToString(","))
                        .apply()
                    allowedHosts.clear()
                    allowedHosts.addAll(cleaned)
                    blockedSites.clear()
                    blockedSites.addAll(workingBlockedSites)
                    adToggle.imageTintList = android.content.res.ColorStateList.valueOf(
                        if (adBlockerEnabled) heading else muted
                    )
                    webView.reload()
                }
                .create()
                .also { settingsDialog ->
                    settingsDialog.setOnShowListener {
                        settingsDialog.window?.setBackgroundDrawable(GradientDrawable().apply {
                            setColor(surface)
                            cornerRadius = dp(20).toFloat()
                            setStroke(dp(1), border)
                        })
                        listOf(
                            android.app.AlertDialog.BUTTON_NEGATIVE,
                            android.app.AlertDialog.BUTTON_POSITIVE
                        ).forEach { which ->
                            settingsDialog.getButton(which)?.apply {
                                setTextColor(accentText)
                                isAllCaps = false
                                setTypeface(typeface, Typeface.BOLD)
                            }
                        }
                    }
                    settingsDialog.show()
                }
        }
        adToggle = android.widget.ImageButton(activity).apply {
            setImageResource(com.marcorx6.library.R.drawable.ic_action_shield)
            imageTintList = android.content.res.ColorStateList.valueOf(if (adBlockerEnabled) heading else muted)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            contentDescription = translate("إعدادات حجب الإعلانات")
            isClickable = true
            isFocusable = true
            setBackground(GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), border)
            })
            setOnClickListener { showAdSettings() }
        }
        val blockedRequests = AtomicInteger(0)
        val blockedBadge = TextView(activity).apply {
            text = "0"
            textSize = 9f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = dp(17)
            minHeight = dp(17)
            setPadding(dp(3), 0, dp(3), 0)
            setBackground(GradientDrawable().apply {
                setColor(Color.rgb(75, 82, 94))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), surface)
            })
        }
        val shieldContainer = android.widget.FrameLayout(activity).apply {
            addView(adToggle, android.widget.FrameLayout.LayoutParams(dp(46), dp(46)))
            addView(blockedBadge, android.widget.FrameLayout.LayoutParams(-2, dp(18), Gravity.END or Gravity.TOP))
        }
        addressRow.addView(shieldContainer, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(7) })
        val activePageUrl = AtomicReference(safeStartUrl)
        fun isBlockedAdRequest(
            url: String,
            requestHeaders: Map<String, String> = emptyMap(),
            isMainFrame: Boolean = false
        ): Boolean = LocalBrowserAdBlocker.shouldBlockRequest(
            url = url,
            pageUrl = activePageUrl.get(),
            enabled = adBlockerEnabled,
            allowedHosts = allowedHosts,
            blockedHosts = blockedSites,
            isDirectFile = ::isDirectFileUrl,
            requestHeaders = requestHeaders,
            isMainFrame = isMainFrame
        )

        fun recordBlocked(amount: Int = 1) {
            if (amount <= 0) return
            val count = blockedRequests.addAndGet(amount)
            activity.runOnUiThread {
                blockedBadge.text = if (count > 999) "999+" else count.toString()
                blockedBadge.contentDescription = translate("عدد الإعلانات المحجوبة: $count")
                blockedStats.text = translate("الإعلانات والمتتبعات المحجوبة: $count")
            }
        }
        fun applyCosmeticFiltering() {
            if (!adBlockerEnabled) return
            val script = LocalBrowserAdBlocker.buildCosmeticScript(activePageUrl.get())
            if (script.isBlank()) return
            webView.evaluateJavascript(script) { raw ->
                val newlyApplied = runCatching {
                    (JSONTokener(raw ?: "0").nextValue() as? Number)?.toInt() ?: 0
                }.getOrDefault(0)
                if (newlyApplied > 0) recordBlocked(newlyApplied)
            }
        }
        var rendererGone = false
        var lastAdRedirectWarningAt = 0L
        fun warnBlockedAdRedirect() {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAdRedirectWarningAt < 4_000L) return
            lastAdRedirectWarningAt = now
            Toast.makeText(
                activity,
                translate("تم حظر تحويل إعلاني غير مرغوب؛ بقيت في الصفحة الأصلية"),
                Toast.LENGTH_SHORT
            ).show()
        }
        LocalBrowserAdBlocker.loadCached(adPreferences) {
            activity.runOnUiThread {
                if (!rendererGone && dialog.isShowing && adBlockerEnabled) applyCosmeticFiltering()
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                val requestUrl = request.url.toString()
                if (isBlockedAdRequest(requestUrl, request.requestHeaders, request.isForMainFrame)) {
                    recordBlocked()
                    return android.webkit.WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                if (scheme != "https") return true
                val destination = request.url.toString()
                if (adBlockerEnabled && isBlockedAdRequest(destination, request.requestHeaders, request.isForMainFrame)) {
                    recordBlocked()
                    if (request.isForMainFrame) warnBlockedAdRedirect()
                    return true
                }
                if (isDirectFileUrl(request.url.toString())) {
                    confirmDownload(request.url.toString(), request.url.lastPathSegment ?: translate("ملف مباشر"))
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                activePageUrl.set(url)
                if (activeTabIndex in browserTabs.indices && url.startsWith("https://")) {
                    browserTabs[activeTabIndex] = browserTabs[activeTabIndex].first to url
                }
                swipeRefresh.isRefreshing = false
                progress.visibility = View.VISIBLE
                progress.progress = 0
                status.text = Uri.parse(url).host ?: translate("جارٍ فتح صفحة التحميل…")
                com.marcorx6.library.AppDiagnostics.log("INFO", "Browser", "Loading host: ${Uri.parse(url).host.orEmpty()}")
                addressInput.setText(url)
                candidates.removeAllViews()
                candidateUrls.clear()
                candidateTitle.visibility = View.GONE
                candidateScroll.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String) {
                rememberPage(url, view.title.orEmpty())
                com.marcorx6.library.AppDiagnostics.log("INFO", "Browser", "Page loaded: ${Uri.parse(url).host.orEmpty()}")
                progress.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                status.text = translate("إذا ظهرت خطوة تحقق أو مؤقت، أكملها داخل هذه الصفحة. الروابط المحتملة تظهر أسفلها.")
                applyCosmeticFiltering()
                scanForFileLinks()
                webView.removeCallbacks(dynamicScanRunnable)
                webView.postDelayed(dynamicScanRunnable, 1_500L)
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                swipeRefresh.isRefreshing = false
                status.text = translate("تعذر التحقق من أمان اتصال الموقع؛ تم إيقاف فتح الصفحة.")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    swipeRefresh.isRefreshing = false
                    status.text = translate("تعذر تحميل صفحة الموقع. جرّب فتحها بالمتصفح الخارجي.")
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail
            ): Boolean {
                // A crashed WebView renderer must be detached and destroyed. Returning
                // true keeps the host app alive instead of propagating the renderer crash.
                rendererGone = true
                com.marcorx6.library.AppDiagnostics.log("ERROR", "Browser", "WebView renderer exited; renderer was detached and destroyed")
                swipeRefresh.isRefreshing = false
                progress.visibility = View.GONE
                status.text = translate("توقف محرك الصفحة. أغلق هذه النافذة ثم أعد فتح صفحة التحميل.")
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                return true
            }
        }
        // Automatic popup attempts are blocked and reported once per short interval,
        // so aggressive ad scripts cannot flood the user with warning messages.
        var lastPopupWarningAt = 0L
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (adBlockerEnabled) {
                    recordBlocked()
                    if (!isUserGesture) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastPopupWarningAt >= 4_000L) {
                            lastPopupWarningAt = now
                            Toast.makeText(
                                activity,
                                translate("تم حظر نافذة منبثقة غير مطلوبة؛ بقيت في الصفحة الأصلية"),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                return false
            }
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val safeUrl = SecureUrlPolicy.toHttpsUrl(url)
            if (safeUrl == null) {
                Toast.makeText(activity, translate("رابط الملف غير آمن؛ تم إيقاف التنزيل"), Toast.LENGTH_LONG).show()
            } else {
                confirmDownload(safeUrl, contentDisposition.ifBlank { gameTitle }, mimeType.orEmpty(), contentDisposition)
            }
        })

        dialog.setContentView(root)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (webView.canGoBack()) {
                    webView.goBack()
                    true
                } else {
                    false
                }
            } else false
        }
        var lifecycleObserver: DefaultLifecycleObserver? = null
        val lifecycleOwner = activity as? LifecycleOwner
        dialog.setOnDismissListener {
            webView.removeCallbacks(dynamicScanRunnable)
            root.animate().cancel()
            com.marcorx6.library.AppDiagnostics.log("INFO", "Browser", "Closed download browser")
            if (!rendererGone) {
                webView.stopLoading()
                webView.onPause()
                webView.destroy()
            }
            lifecycleObserver?.let { lifecycleOwner?.lifecycle?.removeObserver(it) }
        }
        dialog.show()
        dialog.window?.apply {
            setLayout(-1, -1)
            setGravity(Gravity.FILL)
            setBackgroundDrawableResource(android.R.color.transparent)
            // Keep the same immersive top-bar policy as the rest of the app.
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = background
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this, false)
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                val cutout = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                view.setPadding(cutout.left, cutout.top, cutout.right, cutout.bottom)
                insets
            }
            androidx.core.view.ViewCompat.requestApplyInsets(root)
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                attributes = attributes.apply {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            SystemBarPolicy.apply(this, dark, hideNavigation = true)
        }
        addressInput.setText(safeStartUrl)
        val motionEnabled = appPreferences.getBoolean("animations", true)
        if (motionEnabled) {
            root.alpha = 0f
            root.translationY = dp(12).toFloat()
            root.animate().alpha(1f).translationY(0f)
                .setDuration(240L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.35f))
                .start()
        }
        com.marcorx6.library.AppDiagnostics.log("INFO", "Browser", "Opened download browser")
        webView.loadUrl(safeStartUrl)
        LocalBrowserAdBlocker.refreshInBackground(adPreferences) {
            activity.runOnUiThread {
                if (!rendererGone && dialog.isShowing && adBlockerEnabled) {
                    // Apply newly downloaded cosmetic rules without interrupting an active download.
                    applyCosmeticFiltering()
                }
            }
        }
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                if (!rendererGone && dialog.isShowing) webView.onResume()
            }

            override fun onPause(owner: LifecycleOwner) {
                if (!rendererGone) webView.onPause()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                if (dialog.isShowing) dialog.dismiss()
                owner.lifecycle.removeObserver(this)
            }
        }
        lifecycleObserver = observer
        lifecycleOwner?.lifecycle?.addObserver(observer)
    }
}
