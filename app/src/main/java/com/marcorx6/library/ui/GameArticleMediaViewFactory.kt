package com.marcorx6.library.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.marcorx6.library.data.Game
import com.marcorx6.library.ui.details.GameArticleMediaExtractor

internal class GameArticleMediaViewFactory(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val isDarkMode: () -> Boolean,
    private val makeText: (String, Float, Int, Boolean) -> TextView,
    private val primary: () -> Int,
    private val loadCover: (ImageView, String, String) -> Unit
) {
    fun createGallery(game: Game): View? {
        val media = GameArticleMediaExtractor.extract(game.bloggerHtml, game.coverUrl)
        if (media.images.isEmpty()) return null
        val dark = isDarkMode()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(makeText("صور من اللعبة", 15f, primary(), true).apply {
            setPadding(dp(2), 0, 0, dp(9))
        })
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        media.images.forEachIndexed { index, url ->
            val image = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    setColor(if (dark) Color.rgb(35, 38, 45) else Color.rgb(222, 226, 232))
                    cornerRadius = dp(13).toFloat()
                }
                clipToOutline = true
                contentDescription = "صورة ${index + 1} من ${game.name}"
            }
            loadCover(image, "", url)
            image.setOnClickListener { showImage(url, game.name, index + 1) }
            row.addView(image, LinearLayout.LayoutParams(dp(238), dp(142)).apply {
                marginEnd = dp(10)
            })
        }
        root.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(row)
        })
        return root
    }

    fun createYoutubePlayer(game: Game): View? {
        val id = GameArticleMediaExtractor.extract(game.bloggerHtml, game.coverUrl).youtubeVideoId
            ?: return null
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(makeText("فيديو اللعبة", 15f, primary(), true).apply {
            setPadding(dp(2), 0, 0, dp(9))
        })
        val frame = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(14).toFloat()
            }
            clipToOutline = true
        }
        val player = object : WebView(activity) {
            override fun onDetachedFromWindow() {
                stopLoading()
                loadUrl("about:blank")
                destroy()
                super.onDetachedFromWindow()
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = object : WebViewClient() {
                private fun trusted(url: String): Boolean {
                    val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
                    return url.startsWith("https://", true) && host != null &&
                        host in setOf("www.youtube-nocookie.com", "youtube-nocookie.com", "www.youtube.com", "youtube.com")
                }
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    !trusted(url.orEmpty())
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: android.webkit.WebResourceRequest?
                ): Boolean = !trusted(request?.url?.toString().orEmpty())
            }
            loadDataWithBaseURL(
                "https://www.youtube-nocookie.com/",
                """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;width:100%;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style></head><body><iframe src="https://www.youtube-nocookie.com/embed/$id?playsinline=1&rel=0" title="Game video" allow="accelerometer; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe></body></html>""",
                "text/html", "UTF-8", null
            )
        }
        frame.addView(player, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, dp(205)))
        return root
    }

    private fun showImage(url: String, gameName: String, number: Int) {
        val dialog = Dialog(activity)
        val container = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "$gameName - $number"
        }
        loadCover(image, "", url)
        container.addView(image, FrameLayout.LayoutParams(-1, -1))
        container.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(container)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.black)
        dialog.window?.setLayout(-1, -1)
    }
}
