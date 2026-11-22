package com.marcorx6.library.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** One-time welcome screen linking new users to the official MARCO communities. */
internal class CommunityChannelsController(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val makeText: (String, Float, Int, Boolean) -> TextView,
    private val translate: (String) -> String,
    private val isDarkMode: () -> Boolean,
    private val red: () -> Int,
    private val showRootScreen: ((android.view.View) -> Unit)? = null,
    private val onBack: (() -> Unit)? = null
) {
    fun show(onContinue: () -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed) return
        val dark = isDarkMode()
        val dialog = Dialog(activity, android.R.style.Theme_Material_NoActionBar)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(28), dp(24), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (dark) intArrayOf(Color.rgb(7, 8, 11), Color.rgb(15, 17, 22), Color.rgb(7, 8, 11))
                else intArrayOf(Color.rgb(250, 251, 253), Color.rgb(232, 236, 242), Color.rgb(245, 247, 250))
            )
        }
        root.addView(makeText("MARCO", 12f, red(), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.16f
        })
        root.addView(makeText(translate("مرحباً بك في MARCO"), 25f, if (dark) Color.WHITE else Color.rgb(25, 29, 36), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(8))
        })
        root.addView(makeText(
            translate("تابع قنواتنا الرسمية لتصلك أخبار التطبيق والتحديثات الجديدة."),
            13f, if (dark) Color.rgb(178, 184, 194) else Color.rgb(91, 98, 110), false
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(28))
        })
        fun channelButton(label: String, detail: String, url: String) {
            val button = Button(activity).apply {
                text = "$label\n$detail"
                isAllCaps = false
                textSize = 14f
                setTextColor(AccentPalette.foregroundForColor(red()))
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(red())
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.argb(100, 145, 153, 166))
                }
                minHeight = dp(62)
                setOnClickListener {
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                        Toast.makeText(activity, translate("تعذر فتح الرابط"), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            root.addView(button, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
        channelButton("PlayStation 3", "@MARCO_RX1", PS3_TELEGRAM_URL)
        channelButton("PlayStation 4", "@MARCO_RX3", PS4_TELEGRAM_URL)
        channelButton("PlayStation 5", "@MARCO_RX9", PS5_TELEGRAM_URL)
        channelButton("مجتمع MARCO", "@MARCO_RX2", COMMUNITY_TELEGRAM_URL)
        channelButton("YouTube", "@marco_rx7", YOUTUBE_URL)

        val continueButton = Button(activity).apply {
            text = translate("متابعة إلى التطبيق")
            isAllCaps = false
            textSize = 14f
            setTextColor(AccentPalette.foregroundForColor(red()))
            background = GradientDrawable().apply {
                setColor(red())
                cornerRadius = dp(16).toFloat()
            }
            minHeight = dp(52)
            setOnClickListener { dialog.dismiss(); onContinue() }
        }
        root.addView(continueButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        dialog.setContentView(root)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(-1, -1)
        dialog.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            SystemBarPolicy.apply(window, darkMode = dark)
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(dp(24), dp(28) + safe.top, dp(24), dp(24) + safe.bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }

    /** Opens the full-screen community directory from the main app bar. */
    fun showDirectory() {
        if (activity.isFinishing || activity.isDestroyed) return
        val dark = isDarkMode()
        val accent = red()
        val backgroundColor = if (dark) Color.rgb(8, 9, 12) else Color.rgb(245, 247, 250)
        val cardColor = if (dark) Color.rgb(22, 25, 31) else Color.WHITE
        val primaryText = if (dark) Color.rgb(239, 242, 247) else Color.rgb(27, 32, 40)
        val secondaryText = if (dark) Color.rgb(169, 176, 187) else Color.rgb(91, 98, 110)
        val borderColor = if (dark) Color.rgb(65, 72, 83) else Color.rgb(211, 217, 226)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(18))
            setBackgroundColor(backgroundColor)
            tag = "marco_screen:community_channels"
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        val back = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = translate("رجوع")
            setOnClickListener {
                if (onBack != null) onBack.invoke() else activity.finish()
            }
        }
        back.addView(android.widget.ImageView(activity).apply {
            setImageResource(com.marcorx6.library.R.drawable.ic_action_back)
            imageTintList = android.content.res.ColorStateList.valueOf(accent)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        back.addView(makeText(translate("رجوع"), 13f, secondaryText, true).apply {
            setPadding(dp(5), 0, dp(5), 0)
        })
        header.addView(back, LinearLayout.LayoutParams(-2, dp(44)))
        header.addView(makeText(translate("قنوات المجتمع"), 20f, primaryText, true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(android.view.View(activity), LinearLayout.LayoutParams(dp(48), dp(44)))
        root.addView(header)

        val scroll = android.widget.ScrollView(activity).apply {
            clipToPadding = false
            isFillViewport = true
            overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(16))
        }
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val thanks = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(24))
            background = GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), borderColor)
            }
        }
        thanks.addView(makeText("MARCO", 11f, accent, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.18f
        })
        thanks.addView(makeText(
            translate("شكراً لأنك جزء من مجتمع MARCO"),
            21f, primaryText, true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(8))
        })
        thanks.addView(makeText(
            translate("وجودك ودعمك يعنيان لنا الكثير. أنشأنا هذه المساحة لتبقى قريباً من مجتمعنا وتتابع كل جديد. شكراً لثقتك بنا."),
            13f, secondaryText, false
        ).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        content.addView(thanks, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(22)
        })
        content.addView(makeText(translate("قنواتنا الرسمية"), 17f, primaryText, true).apply {
            setPadding(dp(2), 0, dp(2), dp(12))
        })

        fun openChannel(url: String) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(activity, translate("تعذر فتح الرابط"), Toast.LENGTH_SHORT).show()
            }
        }

        fun channelCard(title: String, handle: String, description: String, url: String, icon: Int) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(13), dp(14), dp(13))
                background = GradientDrawable().apply {
                    setColor(cardColor)
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), borderColor)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { openChannel(url) }
            }
            card.addView(android.widget.ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(accent)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                contentDescription = title
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                marginEnd = dp(13)
            })
            val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(makeText(translate(title), 15f, primaryText, true))
            labels.addView(makeText(handle, 11f, accent, true).apply {
                setPadding(0, dp(3), 0, dp(3))
            })
            labels.addView(makeText(translate(description), 11f, secondaryText, false))
            card.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(android.widget.ImageView(activity).apply {
                setImageResource(com.marcorx6.library.R.drawable.ic_action_external)
                imageTintList = android.content.res.ColorStateList.valueOf(secondaryText)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            })
        }

        channelCard("PlayStation 3", "@MARCO_RX1", "قناة أخبار ومحتوى PlayStation 3", PS3_TELEGRAM_URL,
            com.marcorx6.library.R.drawable.ic_community_telegram)
        channelCard("PlayStation 4", "@MARCO_RX3", "قناة أخبار ومحتوى PlayStation 4", PS4_TELEGRAM_URL,
            com.marcorx6.library.R.drawable.ic_community_telegram)
        channelCard("PlayStation 5", "@MARCO_RX9", "قناة أخبار ومحتوى PlayStation 5", PS5_TELEGRAM_URL,
            com.marcorx6.library.R.drawable.ic_community_telegram)
        channelCard("مجتمع MARCO", "@MARCO_RX2", "مساحة مجتمع MARCO والنقاشات", COMMUNITY_TELEGRAM_URL,
            com.marcorx6.library.R.drawable.ic_community_telegram)
        channelCard("YouTube", "@marco_rx7", "فيديوهات وشروحات MARCO", YOUTUBE_URL,
            com.marcorx6.library.R.drawable.ic_community_youtube)

        content.addView(makeText(
            translate("شكراً لوجودك معنا. مجتمع MARCO يكبر بدعمك."),
            12f, secondaryText, false
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(8))
        })
        val target = showRootScreen
        if (target != null) target.invoke(root) else activity.setContentView(root)
    }

    companion object {
        const val PS3_TELEGRAM_URL = "https://t.me/MARCO_RX1"
        const val PS4_TELEGRAM_URL = "https://t.me/MARCO_RX3"
        const val PS5_TELEGRAM_URL = "https://t.me/MARCO_RX9"
        const val COMMUNITY_TELEGRAM_URL = "https://t.me/MARCO_RX2"
        const val YOUTUBE_URL = "https://youtube.com/@marco_rx7?si=udXm_y-QFIZIzZ6a"
    }
}
