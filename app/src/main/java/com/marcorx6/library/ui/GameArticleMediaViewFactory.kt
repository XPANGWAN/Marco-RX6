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
            image.setOnClickListener { showImage(media.images, index, game.name) }
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

    private fun showImage(images: List<String>, initialIndex: Int, gameName: String) {
        if (images.isEmpty()) return
        val dialog = Dialog(activity)
        var currentIndex = initialIndex.coerceIn(images.indices)
        var zoomScale = 1f
        var matrixReady = false
        val imageMatrix = android.graphics.Matrix()
        val container = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "\${gameName} - \${currentIndex + 1}"
            setBackgroundColor(Color.BLACK)
        }
        container.addView(image, FrameLayout.LayoutParams(-1, -1))

        val counter = TextView(activity).apply {
            text = "\${currentIndex + 1} / \${images.size}"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                setColor(0x99000000.toInt())
                cornerRadius = dp(18).toFloat()
            }
        }
        container.addView(counter, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(28)
        })

        val close = TextView(activity).apply {
            text = "✕"
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0x88000000.toInt())
                shape = GradientDrawable.OVAL
            }
            setOnClickListener { dialog.dismiss() }
        }
        container.addView(close, FrameLayout.LayoutParams(dp(46), dp(46)).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            topMargin = dp(18)
            marginEnd = dp(18)
        })

        fun showCurrentImage() {
            zoomScale = 1f
            matrixReady = false
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            image.imageMatrix = android.graphics.Matrix()
            image.contentDescription = "\${gameName} - \${currentIndex + 1}"
            loadCover(image, "", images[currentIndex])
            counter.text = "\${currentIndex + 1} / \${images.size}"
        }

        fun prepareMatrix(): Boolean {
            if (matrixReady) return true
            val drawable = image.drawable ?: return false
            val width = image.width.toFloat()
            val height = image.height.toFloat()
            if (width <= 0f || height <= 0f || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return false
            val fit = minOf(width / drawable.intrinsicWidth, height / drawable.intrinsicHeight)
            val dx = (width - drawable.intrinsicWidth * fit) / 2f
            val dy = (height - drawable.intrinsicHeight * fit) / 2f
            imageMatrix.reset()
            imageMatrix.setScale(fit, fit)
            imageMatrix.postTranslate(dx, dy)
            image.scaleType = ImageView.ScaleType.MATRIX
            image.imageMatrix = imageMatrix
            matrixReady = true
            return true
        }

        val scaleDetector = android.view.ScaleGestureDetector(activity,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: android.view.ScaleGestureDetector): Boolean = prepareMatrix()
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    if (!prepareMatrix()) return false
                    val next = (zoomScale * detector.scaleFactor).coerceIn(1f, 5f)
                    val factor = next / zoomScale
                    imageMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                    zoomScale = next
                    image.imageMatrix = imageMatrix
                    return true
                }
            })

        val gestureDetector = android.view.GestureDetector(activity,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent): Boolean = true
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    if (!prepareMatrix()) return true
                    if (zoomScale > 1f) {
                        zoomScale = 1f
                        image.scaleType = ImageView.ScaleType.FIT_CENTER
                        image.imageMatrix = android.graphics.Matrix()
                        matrixReady = false
                    } else {
                        zoomScale = 2.5f
                        imageMatrix.postScale(zoomScale, zoomScale, e.x, e.y)
                        image.imageMatrix = imageMatrix
                    }
                    return true
                }
                override fun onScroll(
                    first: android.view.MotionEvent?,
                    current: android.view.MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (zoomScale > 1f && prepareMatrix()) {
                        imageMatrix.postTranslate(-distanceX, -distanceY)
                        image.imageMatrix = imageMatrix
                        return true
                    }
                    return false
                }
                override fun onFling(
                    first: android.view.MotionEvent?,
                    last: android.view.MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (zoomScale > 1f || first == null) return false
                    val dx = last.x - first.x
                    if (kotlin.math.abs(dx) < dp(55) || kotlin.math.abs(velocityX) < dp(250)) return false
                    if (dx < 0 && currentIndex < images.lastIndex) {
                        currentIndex++
                        showCurrentImage()
                        return true
                    }
                    if (dx > 0 && currentIndex > 0) {
                        currentIndex--
                        showCurrentImage()
                        return true
                    }
                    return false
                }
            })

        image.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }
        showCurrentImage()
        dialog.setContentView(container)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.black)
        dialog.window?.setLayout(-1, -1)
    }
}
