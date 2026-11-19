package com.sfl.browser.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import com.sfl.browser.R
import kotlin.math.roundToInt

internal fun Context.dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()

internal fun ViewGroup.detach(view: View) {
    (view.parent as? ViewGroup)?.removeView(view)
}

internal fun vertical(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

internal fun horizontal(context: Context) = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

internal fun rounded(context: Context, fill: Int, stroke: Int? = null, radius: Int = 12) =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = context.dp(radius).toFloat()
        stroke?.let { setStroke(context.dp(1), it) }
    }

internal fun browserButton(context: Context, control: BrowserControl, size: ControlSize, selected: Boolean = false) =
    ImageButton(context).apply { styleBrowserButton(this, control, size, selected) }

internal fun styleBrowserButton(button: ImageButton, control: BrowserControl, size: ControlSize, selected: Boolean = false) {
    val context = button.context
    val chromeText = appColor(context, R.color.sfl_cream)
    val accent = appColor(context, R.color.sfl_accent)
    val graphic = AppCompatResources.getDrawable(context, control.icon)?.mutate()
    graphic?.setTint(if (selected) appColor(context, R.color.sfl_on_accent) else chromeText)
    val drawable = graphic?.let { icon ->
        android.graphics.drawable.LayerDrawable(arrayOf(icon)).apply {
            setLayerSize(0, context.dp(size.iconDp), context.dp(size.iconDp))
            setLayerGravity(0, Gravity.CENTER)
        }
    }
    button.setImageDrawable(drawable)
    button.scaleType = android.widget.ImageView.ScaleType.FIT_XY
    button.setPadding(0, 0, 0, 0)
    button.background = RippleDrawable(
        ColorStateList.valueOf((chromeText and 0x00ffffff) or 0x33000000),
        if (selected) rounded(context, accent) else null,
        rounded(context, Color.WHITE),
    )
    button.contentDescription = context.getString(control.label)
    button.tooltipText = button.contentDescription
}

internal fun addChromeControl(
    row: LinearLayout, view: View, control: BrowserControl, layout: BrowserLayout, availableDp: Int,
) {
    val context = row.context
    val size = layout.size(control)
    val address = control == BrowserControl.ADDRESS
    val fill = address && size == ControlSize.FILL
    row.detach(view)
    row.addView(view, LinearLayout.LayoutParams(
        if (fill) 0 else context.dp(layout.minimumWidth(control).coerceAtMost(availableDp)),
        context.dp(if (address) size.heightDp else size.buttonDp),
        if (fill) 1f else 0f,
    ))
}

internal fun renderBrowserChrome(
    host: LinearLayout, bottom: Boolean, layout: BrowserLayout, width: Int,
    views: Map<BrowserControl, View>,
) {
    host.removeAllViews()
    val slots = if (bottom) listOf(BrowserRow.BOTTOM, BrowserRow.BOTTOM_SECOND)
        else listOf(BrowserRow.TOP, BrowserRow.TOP_SECOND)
    val context = host.context
    val available = ((width - context.dp(8)) / context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    for (slot in slots) for (controls in layout.fittedRows(slot, available)) {
        val row = horizontal(context).apply {
            setPadding(context.dp(4), 0, context.dp(4), 0)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        for (control in controls) {
            val view = views.getValue(control)
            if (view is ImageButton) styleBrowserButton(view, control, layout.size(control))
            addChromeControl(row, view, control, layout, available)
        }
        host.addView(row)
    }
    host.visibility = if (host.childCount == 0) View.GONE else View.VISIBLE
}
