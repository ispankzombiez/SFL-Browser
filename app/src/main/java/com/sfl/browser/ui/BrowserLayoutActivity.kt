package com.sfl.browser.ui

import android.content.ClipData
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.sfl.browser.R

class BrowserLayoutActivity : PaletteActivity() {
    private lateinit var draft: BrowserLayout
    private var selected = BrowserControl.ADDRESS
    private lateinit var phone: LinearLayout
    private lateinit var tray: LinearLayout
    private lateinit var inspector: LinearLayout
    private var previewWidth = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draft = savedInstanceState?.getString("draft")?.let(BrowserLayout::decode) ?: BrowserLayout.read(this)
        selected = BrowserControl.entries.getOrElse(savedInstanceState?.getInt("selected") ?: -1) { BrowserControl.ADDRESS }
        val body = appearanceScreen(R.string.browser_layout_title, onSave = {
            BrowserLayout.save(this, draft)
            finish()
        }, onReset = {
            draft = BrowserLayout.default
            selected = BrowserControl.ADDRESS
            render()
        })
        body.addView(appearanceText(R.string.browser_layout_intro, 13))
        phone = vertical(this).apply {
            background = rounded(this@BrowserLayoutActivity, appColor(context, R.color.sfl_page),
                appColor(context, R.color.sfl_ink), radius = 18)
            elevation = dp(4).toFloat()
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        body.addView(phone, fullWidth(14))
        tray = vertical(this)
        body.addView(tray, fullWidth(14))
        inspector = vertical(this)
        body.addView(inspector, fullWidth(14))
        phone.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val now = right - left
            if (now > 0 && previewWidth != now) {
                previewWidth = now
                phone.post(::render)
            }
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", draft.encode())
        outState.putInt("selected", selected.ordinal)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val width = previewWidth.takeIf { it > 0 } ?: dp(resources.configuration.screenWidthDp - 32)
        val available = ((width - dp(20)) / resources.displayMetrics.density).toInt().coerceAtLeast(1)
        phone.removeAllViews()
        drawSlot(phone, BrowserRow.TOP, available)
        drawSlot(phone, BrowserRow.TOP_SECOND, available)
        phone.addView(appearanceText(R.string.browser_preview_site, 13, R.color.sfl_text_black).apply {
            background = rounded(context, appColor(context, R.color.sfl_panel), radius = 8)
            gravity = Gravity.CENTER
            minHeight = dp(74)
        }, fullWidth(5))
        drawSlot(phone, BrowserRow.BOTTOM, available)
        drawSlot(phone, BrowserRow.BOTTOM_SECOND, available)
        tray.removeAllViews()
        drawSlot(tray, BrowserRow.HIDDEN, available)
        renderInspector()
    }

    private fun drawSlot(parent: LinearLayout, position: BrowserRow, room: Int) {
        val slot = vertical(this).apply {
            background = rounded(context, appColor(context, R.color.sfl_page), appColor(context, R.color.sfl_ink), 8)
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
        slot.addView(appearanceText(position.label, 12, R.color.sfl_cream).apply {
            setPadding(dp(6), 0, dp(6), 0)
        })
        dropZone(slot, position)
        val fitted = draft.fittedRows(position, room)
        if (fitted.isEmpty()) slot.addView(appearanceText(R.string.browser_drop_here, 11, R.color.sfl_cream).apply {
            alpha = .78f
            setPadding(dp(6), dp(2), dp(6), 0)
        })
        for (controls in fitted) {
            slot.addView(horizontal(this).apply {
                controls.forEach { control ->
                    val view = controlView(control)
                    addChromeControl(this, view, control, draft, room)
                    dropZone(view, position, control)
                }
            })
        }
        parent.addView(slot, fullWidth(5))
    }

    private fun controlView(control: BrowserControl): View {
        val active = control == selected
        val view = if (control != BrowserControl.ADDRESS) {
            browserButton(this, control, draft.size(control), active)
        } else {
            appearanceText(R.string.browser_preview_url, 12, R.color.sfl_on_field).apply {
                contentDescription = getString(control.label)
                gravity = Gravity.CENTER_VERTICAL
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(10), 0, dp(10), 0)
                background = rounded(context, appColor(context, R.color.sfl_field),
                    appColor(context, if (active) R.color.sfl_accent else R.color.sfl_ink))
            }
        }
        view.setOnClickListener {
            selected = control
            render()
            inspector.announceForAccessibility(getString(control.label))
            inspector.post {
                inspector.requestRectangleOnScreen(android.graphics.Rect(0, 0, inspector.width, inspector.height), false)
            }
        }
        view.setOnLongClickListener {
            view.startDragAndDrop(ClipData.newPlainText("", ""), View.DragShadowBuilder(view), control, 0)
        }
        return view
    }

    private fun dropZone(view: View, slot: BrowserRow, item: BrowserControl? = null) {
        view.setOnDragListener { target, event ->
            val control = event.localState as? BrowserControl ?: return@setOnDragListener false
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> {
                    target.alpha = .56f
                    true
                }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                    target.alpha = 1f
                    true
                }
                DragEvent.ACTION_DROP -> {
                    selected = control
                    val before = if (item != null && event.x > target.width / 2f) {
                        val siblings = draft.rows[slot].orEmpty()
                        siblings.getOrNull(siblings.indexOf(item) + 1)
                    } else item
                    draft = draft.move(control, slot, before)
                    target.alpha = 1f
                    phone.post(::render)
                    true
                }
                else -> true
            }
        }
    }

    private fun renderInspector() {
        val control = selected
        val row = BrowserRow.entries.first { control in draft.rows[it].orEmpty() }
        val peers = draft.rows[row].orEmpty()
        val index = peers.indexOf(control)
        inspector.removeAllViews()
        inspector.apply {
            background = rounded(context, appColor(context, R.color.sfl_panel), appColor(context, R.color.sfl_ink))
            setPadding(dp(10), dp(8), dp(10), dp(4))
        }
        val title = appearanceText(control.label, 17).apply { setTypeface(typeface, Typeface.BOLD) }
        inspector.addView(title)
        val sizes = ControlSize.entries.filter { it != ControlSize.FILL || control == BrowserControl.ADDRESS }
        inspector.addView(horizontal(this).apply {
            sizes.forEach { size ->
                val button = appearanceButton(size.label, draft.size(control) == size) {
                    draft = draft.resize(control, size)
                    render()
                }
                addView(button, LinearLayout.LayoutParams(0, -2, 1f))
            }
        })
        inspector.addView(horizontal(this).apply {
            addView(appearanceButton(R.string.browser_move, false) { chooseRow(row) },
                LinearLayout.LayoutParams(0, -2, 1f))
            val earlier = appearanceButton(R.string.browser_move_earlier, false) {
                draft = draft.move(control, row, peers.getOrNull(index - 1))
                render()
            }
            earlier.isEnabled = index > 0
            addView(earlier, LinearLayout.LayoutParams(0, -2, 1f))
            val later = appearanceButton(R.string.browser_move_later, false) {
                draft = draft.move(control, row, peers.getOrNull(index + 2))
                render()
            }
            later.isEnabled = index < peers.lastIndex
            addView(later, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }

    private fun chooseRow(current: BrowserRow) {
        AlertDialog.Builder(this).setTitle(R.string.browser_move)
            .setSingleChoiceItems(BrowserRow.entries.map { getString(it.label) }.toTypedArray(), current.ordinal) { dialog, index ->
                if (BrowserRow.entries[index] != current) {
                    draft = draft.move(selected, BrowserRow.entries[index])
                    render()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}

internal fun Context.appearanceText(textId: Int, size: Int, color: Int = R.color.sfl_text_black) =
    TextView(this).apply {
        setText(textId)
        textSize = size.toFloat()
        setTextColor(appColor(context, color))
    }

internal fun Context.appearanceButton(label: Int, active: Boolean, click: () -> Unit) =
    androidx.appcompat.widget.AppCompatButton(this).apply {
        setText(label)
        isAllCaps = false
        textSize = 12f
        minHeight = dp(48)
        minimumHeight = dp(48)
        minimumWidth = 0
        minWidth = 0
        setPadding(dp(3), dp(4), dp(3), dp(4))
        setTextColor(appColor(context, if (active) R.color.sfl_on_accent else R.color.sfl_text_black))
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x35000000),
            rounded(context, appColor(context, if (active) R.color.sfl_accent else R.color.sfl_panel_soft),
                appColor(context, R.color.sfl_ink), 8), null,
        )
        setOnClickListener { click() }
    }

internal fun Context.fullWidth(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

internal fun PaletteActivity.appearanceScreen(title: Int, onSave: () -> Unit, onReset: () -> Unit): LinearLayout {
    val root = vertical(this).apply { setBackgroundColor(appColor(context, R.color.sfl_page)) }
    val toolbar = com.google.android.material.appbar.MaterialToolbar(this).apply {
        setTitle(title)
        setTitleTextColor(appColor(context, R.color.sfl_cream))
        setNavigationIcon(R.drawable.ic_arrow_back)
        setNavigationIconTint(appColor(context, R.color.sfl_cream))
        setNavigationContentDescription(R.string.cd_back)
        setNavigationOnClickListener { finish() }
    }
    root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
    val scroll = android.widget.ScrollView(this)
    val body = vertical(this).apply {
        setBackgroundColor(appColor(context, R.color.sfl_ground))
        setPadding(dp(12), dp(12), dp(12), dp(16))
    }
    scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
    root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    root.addView(horizontal(this).apply {
        setPadding(dp(12), dp(6), dp(12), dp(6))
        addView(appearanceButton(R.string.appearance_reset, false, onReset), LinearLayout.LayoutParams(0, -2, 1f))
        addView(appearanceButton(R.string.appearance_save, true, onSave), LinearLayout.LayoutParams(0, -2, 1f))
    })
    setContentView(root)
    SystemBarInsets.apply(window, root)
    NativeFont.apply(root)
    return body
}
