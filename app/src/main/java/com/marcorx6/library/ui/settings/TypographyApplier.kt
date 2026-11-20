package com.marcorx6.library.ui

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Applies typography without compounding font scaling when a live screen is re-skinned.
 * Weak keys ensure detached TextViews are not retained by the cache.
 */
internal object TypographyApplier {
    private val baseTextSizesSp = WeakHashMap<TextView, Float>()

    fun apply(view: View, fontFamily: String, fontScale: Float, scaledDensity: Float) {
        if (view is TextView) {
            val style = view.typeface?.style ?: Typeface.NORMAL
            view.setTypeface(Typeface.create(fontFamily, style))
            val baseSizeSp = synchronized(baseTextSizesSp) {
                baseTextSizesSp[view] ?: (view.textSize / scaledDensity).also {
                    baseTextSizesSp[view] = it
                }
            }
            view.textSize = baseSizeSp * fontScale
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                apply(view.getChildAt(i), fontFamily, fontScale, scaledDensity)
            }
        }
    }
}
