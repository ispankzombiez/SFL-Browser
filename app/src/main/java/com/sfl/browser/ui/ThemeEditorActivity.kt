package com.sfl.browser.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.materialswitch.MaterialSwitch
import com.sfl.browser.R
import java.util.Locale

class ThemeEditorActivity : PaletteActivity() {
    private lateinit var draft: PaletteSelection
    private lateinit var preview: LinearLayout
    private lateinit var presets: LinearLayout
    private lateinit var customControls: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = AppPalette.read(this)
        draft = savedInstanceState?.getBundle("palette")?.let { state ->
            PaletteSelection(
                PalettePreset.entries.find { it.name == state.getString("preset") } ?: saved.preset,
                PaletteColor.entries.filter { state.containsKey(it.name) }.associateWith { state.getInt(it.name) },
                state.getBoolean("legacy"),
            )
        } ?: saved
        val body = appearanceScreen(R.string.palette_title, onSave = {
            AppPalette.save(this, draft)
            finish()
        }, onReset = {
            draft = PaletteSelection(legacyEnabled = draft.legacyEnabled)
            render()
        })
        body.addView(appearanceText(R.string.palette_intro, 13))
        preview = vertical(this).apply { elevation = dp(4).toFloat() }
        body.addView(preview, fullWidth(14))
        presets = vertical(this)
        body.addView(presets, fullWidth(10))
        customControls = vertical(this)
        body.addView(customControls, fullWidth(12))
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("palette", Bundle().apply {
            putString("preset", draft.preset.name)
            putBoolean("legacy", draft.legacyEnabled)
            draft.custom.forEach { (role, color) -> putInt(role.name, color) }
        })
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        renderPreview()
        presets.removeAllViews()
        PalettePreset.entries.chunked(2).forEach { entries ->
            presets.addView(horizontal(this).apply {
                for (preset in entries) {
                    val sample = draft.copy(preset = preset, legacyEnabled = true)
                    val active = draft.preset == preset
                    val card = vertical(context).apply {
                        background = rounded(context, sample.actual(context, PaletteColor.SURFACE),
                            sample.actual(context, if (active) PaletteColor.ACCENT else PaletteColor.BORDER), 9)
                        setPadding(dp(8), dp(7), dp(8), dp(7))
                        minimumHeight = dp(56)
                        gravity = Gravity.CENTER_VERTICAL
                        isSelected = active
                        isFocusable = true
                        setOnClickListener {
                            draft = if (preset == PalettePreset.CUSTOM && draft.custom.isEmpty()) {
                                draft.copy(preset = preset, custom = draft.colors())
                            } else draft.copy(preset = preset)
                            render()
                        }
                    }
                    card.addView(appearanceText(preset.label, 14).apply {
                        setTextColor(sample.actual(context, PaletteColor.TEXT))
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    card.addView(horizontal(context).apply {
                        for (role in listOf(PaletteColor.BACKGROUND, PaletteColor.CANVAS, PaletteColor.FIELD, PaletteColor.ACCENT)) {
                            addView(View(context).apply {
                                background = rounded(context, sample.actual(context, role), radius = 4)
                            }, LinearLayout.LayoutParams(0, dp(7), 1f).apply { marginEnd = dp(3) })
                        }
                    }, fullWidth(6))
                    addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
                }
                if (entries.size == 1) addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            }, fullWidth(6))
        }

        customControls.removeAllViews()
        if (draft.preset != PalettePreset.CUSTOM) return
        if (Build.VERSION.SDK_INT < 30) {
            customControls.addView(MaterialSwitch(this).apply {
                setText(R.string.palette_legacy_toggle)
                setTextColor(appColor(context, R.color.sfl_text_black))
                isChecked = draft.legacyEnabled
                setOnCheckedChangeListener { _, checked ->
                    draft = draft.copy(legacyEnabled = checked)
                    render()
                }
            })
            customControls.addView(appearanceText(R.string.palette_legacy_warning, 12))
        }
        if (Build.VERSION.SDK_INT < 30 && !draft.legacyEnabled) return
        if (draft.hasLowContrast(this)) {
            customControls.addView(appearanceText(R.string.palette_contrast_warning, 13).apply {
                setTypeface(typeface, Typeface.BOLD)
            }, fullWidth(8))
        }
        addSwatches(R.string.palette_group_surfaces, PaletteColor.entries.take(5))
        addSwatches(R.string.palette_group_details, PaletteColor.entries.subList(5, 13))
        addSwatches(R.string.palette_group_status, PaletteColor.entries.drop(13))
    }

    private fun renderPreview() {
        val c: (PaletteColor) -> Int = { draft.actual(this, it) }
        preview.removeAllViews()
        preview.background = rounded(this, c(PaletteColor.BACKGROUND), c(PaletteColor.BORDER), 14)
        preview.setPadding(dp(8), dp(6), dp(8), dp(8))
        preview.addView(appearanceText(R.string.palette_preview, 13).apply {
            setTextColor(c(PaletteColor.CHROME_TEXT))
            setPadding(dp(4), dp(4), dp(4), dp(7))
        })
        preview.addView(vertical(this).apply {
            background = rounded(context, c(PaletteColor.CANVAS), c(PaletteColor.BORDER))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(vertical(context).apply {
                background = rounded(context, c(PaletteColor.SURFACE), c(PaletteColor.BORDER))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                addView(appearanceText(R.string.palette_preview_panel, 16).apply {
                    setTextColor(c(PaletteColor.TEXT))
                    setShadowLayer(dp(1).toFloat(), 0f, dp(1).toFloat(), c(PaletteColor.SHADOW))
                })
                addView(appearanceText(R.string.browser_preview_url, 12).apply {
                    setTextColor(c(PaletteColor.FIELD_TEXT))
                    background = rounded(context, c(PaletteColor.FIELD), c(PaletteColor.BORDER), 8)
                    setPadding(dp(7), dp(7), dp(7), dp(7))
                }, fullWidth(6))
                addView(appearanceText(R.string.palette_preview_button, 12).apply {
                    background = rounded(context, c(PaletteColor.ACCENT), c(PaletteColor.BORDER), 8)
                    setTextColor(c(PaletteColor.ON_ACCENT))
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
                addView(horizontal(context).apply {
                    for ((role, textRole) in listOf(
                        PaletteColor.READY to PaletteColor.ON_READY, PaletteColor.SOON to PaletteColor.ON_SOON,
                        PaletteColor.LATER to PaletteColor.ON_LATER, PaletteColor.DANGER to PaletteColor.ON_DANGER,
                        PaletteColor.NEUTRAL to PaletteColor.ON_NEUTRAL,
                    )) {
                        addView(appearanceText(role.label, 10).apply {
                            setTextColor(c(textRole))
                            background = rounded(context, c(role), radius = 4)
                            gravity = Gravity.CENTER
                            setPadding(dp(2), dp(4), dp(2), dp(4))
                        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(3) })
                    }
                }, fullWidth(8))
            })
        })
    }

    private fun addSwatches(title: Int, roles: List<PaletteColor>) {
        customControls.addView(appearanceText(title, 16).apply { setTypeface(typeface, Typeface.BOLD) }, fullWidth(8))
        roles.chunked(2).forEach { pair ->
            customControls.addView(horizontal(this).apply {
                pair.forEach { role ->
                    val value = draft.actual(context, role)
                    val tile = horizontal(context).apply {
                        background = rounded(context, appColor(context, R.color.sfl_panel), appColor(context, R.color.sfl_ink), 8)
                        setPadding(dp(5), 0, dp(5), 0)
                        minimumHeight = dp(48)
                        isFocusable = true
                        setOnClickListener { showPicker(role) }
                        addView(View(context).apply {
                            background = rounded(context, value, appColor(context, R.color.sfl_ink), 5)
                        }, LinearLayout.LayoutParams(dp(21), dp(25)).apply { marginEnd = dp(6) })
                        addView(appearanceText(role.label, 11), LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
                }
                if (pair.size == 1) addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            }, fullWidth(4))
        }
    }

    private fun showPicker(role: PaletteColor) {
        val hsv = FloatArray(3)
        val pane = vertical(this).apply { setPadding(dp(20), dp(6), dp(20), dp(12)) }
        val sample = View(this)
        pane.addView(sample, LinearLayout.LayoutParams(-1, dp(36)))
        val input = EditText(this).apply {
            setSingleLine()
            setHint(R.string.palette_hex_hint)
            filters = arrayOf(InputFilter.LengthFilter(7))
        }
        pane.addView(input)
        val limits = intArrayOf(359, 100, 100)
        var binding = false
        val sliders = listOf(R.string.palette_hue, R.string.palette_saturation, R.string.palette_brightness)
            .mapIndexed { i, label ->
                pane.addView(appearanceText(label, 12))
                SeekBar(this).apply {
                    max = limits[i]
                    contentDescription = getString(label)
                    pane.addView(this)
                }
            }
        fun update(color: Int, fromHex: Boolean) {
            binding = true
            Color.colorToHSV(color, hsv)
            sample.background = rounded(this, color, appColor(this, R.color.sfl_ink))
            if (!fromHex) input.setText(hexColor(color))
            for (i in hsv.indices) sliders[i].progress = (hsv[i] * if (i == 0) 1 else 100).toInt()
            binding = false
        }
        input.doAfterTextChanged {
            if (!binding) {
                val color = parseHexColor(it?.toString().orEmpty())
                if (color != null) {
                    input.error = null
                    update(color, fromHex = true)
                }
            }
        }
        sliders.forEachIndexed { index, slider ->
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!binding && fromUser) {
                        hsv[index] = if (index == 0) progress.toFloat() else progress / 100f
                        input.setText(hexColor(Color.HSVToColor(hsv)))
                    }
                }
            })
        }
        update(draft.actual(this, role), fromHex = false)
        AlertDialog.Builder(this).setTitle(role.label).setView(pane)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.palette_use_color, null).create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val color = parseHexColor(input.text.toString())
                        if (color == null) {
                            input.error = getString(R.string.palette_invalid_hex)
                        } else {
                            draft = draft.copy(custom = draft.custom + (role to color))
                            render()
                            dismiss()
                        }
                    }
                }
                show()
            }
    }

    private fun hexColor(color: Int): String = String.format(Locale.ROOT, "#%06X", color and 0xffffff)
}

internal fun parseHexColor(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
    return hex.toInt(16) or 0xff000000.toInt()
}
