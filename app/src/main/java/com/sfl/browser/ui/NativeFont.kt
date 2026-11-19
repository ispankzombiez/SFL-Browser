package com.sfl.browser.ui

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import java.util.WeakHashMap

object NativeFont {
    val percentages: List<Int> = listOf(85, 100, 115, 130)
    private const val DEFAULT_PERCENT = 100
    private val sizes = WeakHashMap<TextView, TextSize>()

    fun percent(context: Context): Int =
        prefs(context).getInt("percent", DEFAULT_PERCENT).takeIf { it > 0 } ?: DEFAULT_PERCENT

    fun setPercent(context: Context, percent: Int) {
        require(percent > 0)
        prefs(context).edit().putInt("percent", percent).apply()
    }

    /** Apply on the UI thread after inflation or view binding. Never changes WebViews or configuration. */
    fun apply(root: View) {
        apply(root, percent(root.context) / 100f)
        LegacyPalette.apply(root)
        applyPaletteSurfaces(root)
    }

    private fun apply(view: View, scale: Float) {
        if (view is WebView) return
        if (view is TextView) {
            val current = view.textSize
            // Adapters can explicitly set a fresh base size on a recycled TextView.
            val base = sizes[view]?.takeIf { it.appliedPx == current }?.basePx ?: current
            val target = base * scale
            if (target != current) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, target)
            sizes[view] = TextSize(base, view.textSize)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) apply(view.getChildAt(index), scale)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("native_font", Context.MODE_PRIVATE)
    private data class TextSize(val basePx: Float, val appliedPx: Float)
}
