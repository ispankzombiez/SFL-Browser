package com.sfl.browser.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.sfl.browser.R
import com.sfl.browser.data.HomeTab

internal class TabSwitcher(
    context: Context,
    private val tabs: List<HomeTab>,
    private val active: String,
    private val onSelect: (HomeTab) -> Unit,
) : Dialog(context, R.style.Theme_SflBrowser) {
    private val pages = tabs.chunked(16)
    private var page = tabs.indexOfFirst { it.id == active }.coerceAtLeast(0) / 16
    private val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val indicator = text("", 14f).apply { gravity = Gravity.CENTER }
    private val previous = Button(context).apply {
        setText(R.string.tab_previous_page)
        setOnClickListener { page--; render() }
    }
    private val next = Button(context).apply {
        setText(R.string.tab_next_page)
        setOnClickListener { page++; render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.sfl_page))
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text(context.getString(R.string.tab_switcher_title), 20f), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(context).apply {
            setText(android.R.string.cancel)
            setOnClickListener { dismiss() }
        })
        root.addView(header)
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        if (pages.size > 1) root.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(previous, LinearLayout.LayoutParams(0, -2, 1f))
            addView(indicator, LinearLayout.LayoutParams(0, -2, 0.6f))
            addView(next, LinearLayout.LayoutParams(0, -2, 1f))
        })
        setContentView(root)
        window?.let { SystemBarInsets.apply(it, root) }
        NativeFont.apply(root)
        render()
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    private fun render() {
        grid.removeAllViews()
        val items = pages[page]
        repeat(4) { row ->
            val line = LinearLayout(context)
            grid.addView(line, LinearLayout.LayoutParams(-1, 0, 1f))
            repeat(4) { column ->
                val tab = items.getOrNull(row * 4 + column)
                val cell = if (tab == null) View(context) else tile(tab)
                line.addView(cell, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    val gap = (context.resources.displayMetrics.density * 2).toInt()
                    setMargins(gap, gap, gap, gap)
                })
            }
        }
        previous.isEnabled = page > 0
        next.isEnabled = page < pages.lastIndex
        indicator.text = context.getString(R.string.tab_page, page + 1, pages.size)
        NativeFont.apply(grid)
    }

    private fun tile(tab: HomeTab) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isFocusable = true
        isSelected = tab.id == active
        setBackgroundColor(context.getColor(if (isSelected) R.color.sfl_panel else R.color.sfl_panel_soft))
        contentDescription = "${tab.name}, ${tab.url}"
        val preview = TabPreviews.load(context, tab)
        val body = if (preview != null) {
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(preview)
            }
        } else text(tab.url, 11f, Color.BLACK).apply { maxLines = 3; gravity = Gravity.CENTER }
        addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(text(tab.name, 12f, Color.BLACK).apply { gravity = Gravity.CENTER })
        setOnClickListener { dismiss(); onSelect(tab) }
    }

    private fun text(value: String, size: Float, color: Int = Color.WHITE) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
}
