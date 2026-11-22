package com.marcorx6.library.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Html
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import com.marcorx6.library.data.Game

internal class GameDetailsIdentityViewFactory(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val makeText: (String, Float, Int, Boolean) -> TextView,
    private val isDarkMode: () -> Boolean,
    private val red: () -> Int,
    private val loadCover: (ImageView, String, String) -> Unit,
    private val loadDownloads: (Game, (Long) -> Unit) -> Unit,
    private val translate: (String) -> String,
    private val targetLanguage: () -> String,
    private val favoriteToggleFactory: GameFavoriteToggleFactory
) {

    fun create(game: Game): LinearLayout {
        val dark = isDarkMode()
        val muted = if (dark) Color.rgb(178, 184, 194) else Color.rgb(91, 98, 110)
        val primary = if (dark) Color.rgb(244, 247, 252) else Color.rgb(25, 29, 36)
        val cardColor = if (dark) Color.rgb(25, 28, 34) else Color.rgb(242, 244, 247)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = android.view.View.LAYOUT_DIRECTION_LOCALE
        }

        // Compact identity header: cover and title side by side, with a real download counter.
        val gameCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutDirection = android.view.View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (dark) intArrayOf(
                    AccentPalette.surface(Color.rgb(25, 28, 34), red(), 0.13f),
                    AccentPalette.surface(Color.rgb(8, 9, 12), red(), 0.10f)
                ) else intArrayOf(
                    AccentPalette.surface(Color.WHITE, red(), 0.07f),
                    AccentPalette.surface(Color.rgb(242, 244, 247), red(), 0.10f)
                )
            ).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), if (dark)
                    AccentPalette.surface(Color.rgb(130, 138, 151), red(), 0.38f)
                else AccentPalette.surface(Color.rgb(160, 168, 181), red(), 0.32f))
            }
            elevation = dp(3).toFloat()
        }

        val cover = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(if (dark) Color.rgb(35, 38, 45) else Color.rgb(222, 226, 232))
                cornerRadius = dp(13).toFloat()
            }
            clipToOutline = true
            contentDescription = "${translate("غلاف اللعبة")} ${game.name}"
        }
        if (game.coverUrl.isNotBlank() || game.coverLocalPath.isNotBlank()) {
            loadCover(cover, game.coverLocalPath, game.coverUrl)
        } else {
            cover.setImageResource(android.R.drawable.ic_menu_gallery)
        }
        gameCard.addView(cover, LinearLayout.LayoutParams(dp(112), dp(168)))

        val identityInfo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(3), dp(3), dp(3))
        }
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutDirection = android.view.View.LAYOUT_DIRECTION_LOCALE
        }
        val titleColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleColumn.addView(makeText(game.name, 17f, primary, true).apply {
            gravity = android.view.Gravity.START
            textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
            maxLines = 3
            setLineSpacing(dp(1).toFloat(), 1.02f)
            contentDescription = game.name
        }, LinearLayout.LayoutParams(-1, -2))
        if (game.platform.isNotBlank()) {
            titleColumn.addView(makeText(translate(game.platform), 11f, muted, false).apply {
                gravity = android.view.Gravity.START
                textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
                maxLines = 1
                setPadding(0, dp(3), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        titleRow.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(favoriteToggleFactory.create(game), LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginStart = dp(20)
        })
        identityInfo.addView(titleRow, LinearLayout.LayoutParams(-1, -2))

        val downloadRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(11), dp(7))
            background = GradientDrawable().apply {
                setColor(if (dark)
                    AccentPalette.surface(Color.rgb(35, 38, 45), red(), 0.16f)
                else AccentPalette.surface(Color.rgb(232, 234, 238), red(), 0.10f))
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1), if (dark)
                    AccentPalette.surface(Color.rgb(105, 113, 126), red(), 0.34f)
                else AccentPalette.surface(Color.rgb(190, 198, 210), red(), 0.30f))
            }
            contentDescription = translate("عدد التنزيلات")
        }
        val downloadIcon = ImageView(activity).apply {
            setImageResource(com.marcorx6.library.R.drawable.ic_action_download)
            imageTintList = android.content.res.ColorStateList.valueOf(
                if (dark) Color.rgb(225, 228, 234) else Color.rgb(65, 71, 80)
            )
            contentDescription = null
        }
        downloadRow.addView(downloadIcon, LinearLayout.LayoutParams(dp(16), dp(16)))
        val downloadCount = makeText(translate("التنزيلات: 0"), 11f, primary, true).apply {
            setPadding(dp(6), 0, 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        downloadRow.addView(downloadCount, LinearLayout.LayoutParams(-2, -2))
        // Place the download counter at the far end of the game card,
        // instead of directly underneath the title.
        identityInfo.addView(downloadRow, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(10)
            gravity = android.view.Gravity.RIGHT
        })
        gameCard.addView(identityInfo, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(gameCard, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
        loadDownloads(game) { count ->
            activity.runOnUiThread {
                downloadCount.text = "${translate("التنزيلات")}: ${count.coerceAtLeast(0)}"
            }
        }

        val details = extractGameDetails(game)
        if (details.isNotEmpty()) {
            val infoCard = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(12), dp(13), dp(12))
                background = GradientDrawable().apply {
                    setColor(AccentPalette.surface(cardColor, red(), if (dark) 0.10f else 0.07f))
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(1), if (dark)
                        AccentPalette.surface(Color.rgb(75, 80, 90), red(), 0.34f)
                    else AccentPalette.surface(Color.rgb(211, 215, 222), red(), 0.30f))
                }
                elevation = dp(2).toFloat()
            }
            infoCard.addView(makeText(translate("معلومات اللعبة"), 16f, primary, true).apply {
                setPadding(0, 0, 0, dp(10))
            })
            val detailRows = details.chunked(2)
            detailRows.forEach { rowItems ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = android.view.View.LAYOUT_DIRECTION_LOCALE
                }
                rowItems.forEachIndexed { index, item ->
                    val cell = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        layoutDirection = android.view.View.LAYOUT_DIRECTION_LOCALE
                        setPadding(dp(9), dp(10), dp(9), dp(10))
                        background = GradientDrawable().apply {
                            setColor(if (dark) Color.rgb(43, 47, 55) else Color.WHITE)
                            cornerRadius = dp(11).toFloat()
                        }
                    }
                    val iconResource = when (item.first) {
                        "الحجم" -> android.R.drawable.ic_menu_manage
                        "النسخة" -> android.R.drawable.ic_menu_info_details
                        "الصيغة" -> android.R.drawable.ic_menu_save
                        "اللغة" -> android.R.drawable.ic_menu_set_as
                        "كلمة السر" -> android.R.drawable.ic_lock_lock
                        "الحقوق" -> android.R.drawable.ic_menu_myplaces
                        "النوع" -> android.R.drawable.ic_menu_sort_by_size
                        "سنة الإصدار" -> android.R.drawable.ic_menu_recent_history
                        else -> android.R.drawable.ic_menu_info_details
                    }
                    val icon = ImageView(activity).apply {
                        setImageResource(iconResource)
                        imageTintList = android.content.res.ColorStateList.valueOf(muted)
                        contentDescription = null
                    }
                    cell.addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                        marginEnd = dp(7)
                    })
                    val textColumn = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    textColumn.addView(makeText(translate(item.first), 11f, muted, false).apply {
                        textDirection = android.view.View.TEXT_DIRECTION_FIRST_STRONG
                        gravity = android.view.Gravity.START
                        maxLines = 2
                    }, LinearLayout.LayoutParams(-1, -2))
                    textColumn.addView(makeText(item.second, 13f, primary, true).apply {
                        textDirection = android.view.View.TEXT_DIRECTION_FIRST_STRONG
                        gravity = android.view.Gravity.START
                        textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
                        setPadding(0, dp(3), 0, 0)
                        maxLines = 4
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setHorizontallyScrolling(false)
                    }, LinearLayout.LayoutParams(-1, -2))
                    cell.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        if (index == 0 && rowItems.size == 2) marginEnd = dp(6)
                    })
                }
                if (rowItems.size == 1) {
                    row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
                }
                infoCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(7)
                })
            }
            root.addView(infoCard, LinearLayout.LayoutParams(-1, -2))
        } else {
            root.addView(makeText(
                translate("لا تتوفر معلومات إضافية لهذه اللعبة"),
                13f,
                muted,
                false
            ).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
        }

        return root
    }

    /**
     * Only translate human-readable language and genre values.
     * Versions, sizes, formats, passwords, credits and release years may contain
     * identifiers or technical data and must remain byte-for-byte unchanged.
     */
    private fun shouldTranslateDetailValue(label: String, value: String): Boolean {
        if (label !in setOf("اللغة", "النوع") || value.length < 3) return false
        if (Regex("(?i)^(https?://|www\\.)").containsMatchIn(value)) return false
        if (Regex("^[\\d\\s.,:/+%×xX-]+$").matches(value)) return false
        return true
    }

    private fun extractGameDetails(game: Game): List<Pair<String, String>> {
        val html = game.bloggerHtml
        val stored = linkedMapOf(
            "الحجم" to game.packageSize,
            "النسخة" to game.version,
            "الصيغة" to game.fileFormat,
            "اللغة" to game.language,
            "كلمة السر" to game.password,
            "الحقوق" to game.rights,
            "النوع" to game.genre,
            "سنة الإصدار" to game.releaseYear
        ).filterValues { it.isNotBlank() }.toMutableMap()
        if (html.isBlank()) return stored
            .filterValues { !it.normalizedForDetails().equals(game.name.normalizedForDetails(), ignoreCase = true) }
            .map { it.key to it.value }
        val fields = listOf(
            "الحجم" to listOf("الحجم", "حجم", "size"),
            "النسخة" to listOf("النسخة", "version", "title id", "cusa"),
            "الصيغة" to listOf("الصيغة", "format"),
            "اللغة" to listOf("اللغة", "language", "languages"),
            "كلمة السر" to listOf("كلمة السر", "كلمه السر", "password", "pass"),
            "الحقوق" to listOf("الحقوق", "credits", "rights"),
            "النوع" to listOf("النوع", "genre", "category"),
            "سنة الإصدار" to listOf("سنة الإصدار", "سنة الاصدار", "release year", "year")
        )
        fun plainText(value: String): String = Html.fromHtml(
            value, Html.FROM_HTML_MODE_LEGACY
        ).toString().replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()

        val entries = linkedMapOf<String, String>().apply { putAll(stored) }
        Regex("(?is)<li\\b[^>]*>(.*?)</li\\s*>").findAll(html).forEach { item ->
            val text = plainText(item.groupValues[1]).trim(' ', ':', '：', '-', '•')
            fields.forEach { (label, aliases) ->
                val alias = aliases.joinToString("|") { Regex.escape(it) }
                val match = Regex("(?i)^\\s*(?:$alias)\\s*[:：-]\\s*(.*?)\\s*$").find(text)
                val value = match?.groupValues?.get(1)?.trim()?.trimEnd('.', ',', ';')
                if (!value.isNullOrBlank()) entries[label] = value
            }
        }

        if (entries.size < fields.size) {
            val plain = plainText(
                html.replace(Regex("(?i)<br\\s*/?>"), "\\n")
                    .replace(Regex("(?i)</(p|div|tr|h[1-6]|blockquote)\\s*>"), "\\n")
            )
            fields.forEach { (label, aliases) ->
                if (entries[label].isNullOrBlank()) {
                    val alias = aliases.joinToString("|") { Regex.escape(it) }
                    val match = Regex("(?i)(?:^|[\\n\\r])\\s*(?:$alias)\\s*[:：-]\\s*([^\\n\\r]+)").find(plain)
                    match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }?.let {
                        entries[label] = it.trimEnd('.', ',', ';')
                    }
                }
            }
        }
        val normalizedGameName = game.name.normalizedForDetails()
        return fields.mapNotNull { (label, _) ->
            entries[label]?.takeIf {
                it.isNotBlank() &&
                    !it.normalizedForDetails().equals(normalizedGameName, ignoreCase = true)
            }?.let { value -> label to value }
        }
    }

    private fun String.normalizedForDetails(): String =
        replace(Regex("\\s+"), " ").trim().removeSuffix(".").removeSuffix(",")
}
