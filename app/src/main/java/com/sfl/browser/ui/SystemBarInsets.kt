package com.sfl.browser.ui

import android.view.View
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sfl.browser.data.Prefs

object SystemBarInsets {
    fun apply(window: Window, root: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                left + bars.left,
                top + bars.top,
                right + bars.right,
                bottom + maxOf(bars.bottom, ime.bottom),
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
        updateNavigation(window)
    }

    fun updateNavigation(window: Window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val navigation = WindowInsetsCompat.Type.navigationBars()
        if (Prefs.hideAndroidNavigation(window.context)) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(navigation)
        } else {
            controller.show(navigation)
        }
    }
}
