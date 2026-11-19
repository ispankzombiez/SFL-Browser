package com.sfl.browser.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.ColorResourcesOverride
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.sfl.browser.R
import java.util.WeakHashMap

internal enum class PalettePreset(val label: Int) {
    DEFAULT(R.string.palette_default), DARK(R.string.palette_dark),
    CONTRAST(R.string.palette_contrast), BLUE_DUSK(R.string.palette_blue_dusk),
    MOSS_AND_GOLD(R.string.palette_moss_gold), BERRY_JAM(R.string.palette_berry_jam),
    CUSTOM(R.string.palette_custom),
}

internal enum class PaletteColor(val label: Int, val res: Int) {
    BACKGROUND(R.string.palette_background, R.color.sfl_page),
    CANVAS(R.string.palette_canvas, R.color.sfl_ground),
    SURFACE(R.string.palette_surface, R.color.sfl_panel),
    CARD(R.string.palette_card, R.color.sfl_panel_soft),
    FIELD(R.string.palette_field, R.color.sfl_field),
    FIELD_TEXT(R.string.palette_field_text, R.color.sfl_on_field),
    TEXT(R.string.palette_text, R.color.sfl_text_black),
    CHROME_TEXT(R.string.palette_chrome_text, R.color.sfl_cream),
    POPUP_TEXT(R.string.palette_popup_text, R.color.sfl_text_white),
    BORDER(R.string.palette_border, R.color.sfl_ink),
    SHADOW(R.string.palette_shadow, R.color.sfl_ink_shadow),
    ACCENT(R.string.palette_accent, R.color.sfl_accent),
    ON_ACCENT(R.string.palette_on_accent, R.color.sfl_on_accent),
    READY(R.string.palette_ready, R.color.sfl_chip_ready),
    ON_READY(R.string.palette_on_ready, R.color.sfl_on_chip_ready),
    SOON(R.string.palette_soon, R.color.sfl_chip_soon),
    ON_SOON(R.string.palette_on_soon, R.color.sfl_on_chip_soon),
    LATER(R.string.palette_later, R.color.sfl_chip_later),
    ON_LATER(R.string.palette_on_later, R.color.sfl_on_chip_later),
    DANGER(R.string.palette_danger, R.color.sfl_chip_danger),
    ON_DANGER(R.string.palette_on_danger, R.color.sfl_on_chip_danger),
    NEUTRAL(R.string.palette_neutral, R.color.sfl_chip_default),
    ON_NEUTRAL(R.string.palette_on_neutral, R.color.sfl_on_chip_default),
}

internal data class PaletteSelection(
    val preset: PalettePreset = PalettePreset.DEFAULT,
    val custom: Map<PaletteColor, Int> = emptyMap(),
    val legacyEnabled: Boolean = false,
) {
    private fun presetColors(): Map<PaletteColor, Int> = when (preset) {
        PalettePreset.DEFAULT -> emptyMap()
        PalettePreset.DARK -> mapOf(
            // Sunflower Land dark mode deepens the tan panels instead of making them black.
            PaletteColor.BACKGROUND to 0xff191422.toInt(),
            PaletteColor.CANVAS to 0xffa66c55.toInt(),
            PaletteColor.SURFACE to 0xffc28669.toInt(),
            PaletteColor.CARD to 0xffd49a7a.toInt(),
            PaletteColor.FIELD to 0xffeed4b3.toInt(),
        )
        PalettePreset.CONTRAST -> mapOf(
            PaletteColor.BACKGROUND to Color.BLACK, PaletteColor.CANVAS to 0xff171717.toInt(),
            PaletteColor.SURFACE to 0xff202020.toInt(), PaletteColor.CARD to 0xff292929.toInt(),
            PaletteColor.TEXT to Color.WHITE, PaletteColor.CHROME_TEXT to Color.WHITE,
            PaletteColor.FIELD to Color.WHITE, PaletteColor.FIELD_TEXT to Color.BLACK,
            PaletteColor.BORDER to 0xffbfbfbf.toInt(), PaletteColor.SHADOW to Color.BLACK,
            PaletteColor.ACCENT to 0xffffe100.toInt(), PaletteColor.ON_ACCENT to Color.BLACK,
            PaletteColor.READY to 0xff81ff9a.toInt(), PaletteColor.ON_READY to Color.BLACK,
            PaletteColor.SOON to 0xffffe100.toInt(), PaletteColor.ON_SOON to Color.BLACK,
            PaletteColor.LATER to 0xff94d7ff.toInt(), PaletteColor.ON_LATER to Color.BLACK,
            PaletteColor.DANGER to 0xffff9292.toInt(), PaletteColor.ON_DANGER to Color.BLACK,
            PaletteColor.NEUTRAL to 0xffe6e6e6.toInt(),
        )
        PalettePreset.BLUE_DUSK -> coordinated(
            0x121a30, 0x212e4a, 0x2d3e61, 0x3c5178, 0xddeaff, 0x10213e,
            0xf3f7ff, 0xb9d3ff, 0x748eb9, 0xa2c1ff, 0x071831,
        )
        PalettePreset.MOSS_AND_GOLD -> coordinated(
            0x172017, 0xc3bb8c, 0xe9e0ac, 0xf6efcf, 0xffffe8, 0x243020,
            0x222b1b, 0xffe6a3, 0x485c38, 0xd3a13b, 0x30210a,
        )
        PalettePreset.BERRY_JAM -> coordinated(
            0x271529, 0x563048, 0x673c5a, 0x7b4a69, 0xffe4ef, 0x351627,
            0xfff3f8, 0xffdcf0, 0xad779b, 0xffbacf, 0x351528,
        )
        PalettePreset.CUSTOM -> custom
    }

    private fun coordinated(
        background: Int, canvas: Int, surface: Int, card: Int, field: Int, fieldText: Int,
        text: Int, chromeText: Int, border: Int, accent: Int, onAccent: Int,
    ): Map<PaletteColor, Int> = mapOf(
        PaletteColor.BACKGROUND to background, PaletteColor.CANVAS to canvas, PaletteColor.SURFACE to surface,
        PaletteColor.CARD to card, PaletteColor.FIELD to field, PaletteColor.FIELD_TEXT to fieldText,
        PaletteColor.TEXT to text, PaletteColor.CHROME_TEXT to chromeText, PaletteColor.POPUP_TEXT to chromeText,
        PaletteColor.BORDER to border, PaletteColor.SHADOW to background, PaletteColor.ACCENT to accent,
        PaletteColor.ON_ACCENT to onAccent,
    ).mapValues { (_, rgb) -> rgb or 0xff000000.toInt() }

    fun colors() = if (preset == PalettePreset.CUSTOM && Build.VERSION.SDK_INT < 30 && !legacyEnabled) {
        emptyMap()
    } else presetColors()

    fun actual(context: Context, color: PaletteColor): Int =
        colors()[color] ?: context.applicationContext.getColor(color.res)

    fun hasLowContrast(context: Context): Boolean = contrastPairs.any { (text, background) ->
        ColorUtils.calculateContrast(actual(context, text), actual(context, background)) < 4.5
    }

    companion object {
        private val contrastPairs = listOf(
            PaletteColor.CHROME_TEXT to PaletteColor.BACKGROUND, PaletteColor.POPUP_TEXT to PaletteColor.BACKGROUND,
            PaletteColor.TEXT to PaletteColor.CANVAS, PaletteColor.TEXT to PaletteColor.SURFACE,
            PaletteColor.TEXT to PaletteColor.CARD, PaletteColor.FIELD_TEXT to PaletteColor.FIELD,
            PaletteColor.ON_ACCENT to PaletteColor.ACCENT, PaletteColor.ON_READY to PaletteColor.READY,
            PaletteColor.ON_SOON to PaletteColor.SOON, PaletteColor.ON_LATER to PaletteColor.LATER,
            PaletteColor.ON_DANGER to PaletteColor.DANGER, PaletteColor.ON_NEUTRAL to PaletteColor.NEUTRAL,
        )
    }
}

internal object AppPalette {
    private val loaded = WeakHashMap<Context, Boolean>()

    fun read(context: Context): PaletteSelection {
        val prefs = prefs(context)
        val preset = PalettePreset.entries.find { it.name == prefs.getString("preset", null) } ?: PalettePreset.DEFAULT
        val custom = PaletteColor.entries.filter { prefs.contains("custom.${it.name}") }
            .associateWith { prefs.getInt("custom.${it.name}", Color.BLACK) or 0xff000000.toInt() }
        return PaletteSelection(preset, custom, prefs.getBoolean("legacy", false))
    }

    fun save(context: Context, selection: PaletteSelection) {
        prefs(context).edit().apply {
            putString("preset", selection.preset.name)
            putBoolean("legacy", selection.legacyEnabled)
            PaletteColor.entries.forEach { color ->
                val value = selection.custom[color]
                if (value == null) remove("custom.${color.name}") else putInt("custom.${color.name}", value)
            }
        }.apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("app_palette", Context.MODE_PRIVATE)

    @Suppress("RestrictedApi")
    fun wrap(context: Context, selection: PaletteSelection): Context {
        val colors = selection.colors().mapKeys { it.key.res }
        if (colors.isEmpty() || Build.VERSION.SDK_INT < 30) return context
        val wrapped = ColorResourcesOverride.getInstance()?.wrapContextIfPossible(context, colors) ?: context
        if (wrapped === context) Log.w("AppPalette", "Native color override unavailable; using view-based colors")
        else loaded[wrapped] = true
        return wrapped
    }

    fun usesNativePalette(context: Context): Boolean {
        var current = context
        while (true) {
            if (loaded[current] == true) return true
            val base = (current as? android.content.ContextWrapper)?.baseContext ?: return false
            if (base === current) return false
            current = base
        }
    }
}

internal fun appColor(context: Context, res: Int): Int =
    PaletteColor.entries.firstOrNull { it.res == res }?.let { AppPalette.read(context).actual(context, it) }
        ?: context.getColor(res)

internal fun applyPaletteBackground(view: View, fill: Int, radius: Int) {
    val context = view.context
    val padding = intArrayOf(view.paddingStart, view.paddingTop, view.paddingEnd, view.paddingBottom)
    view.background = rounded(context, appColor(context, fill), appColor(context, R.color.sfl_ink), radius)
    view.setPaddingRelative(padding[0], padding[1], padding[2], padding[3])
}

internal fun applyPaletteSurfaces(root: View) {
    val selection = AppPalette.read(root.context)
    if (selection.colors().isEmpty()) return
    fun apply(view: View) {
        when (view.tag) {
            "palette-canvas" -> applyPaletteBackground(view, R.color.sfl_ground, 8)
            "palette-panel" -> applyPaletteBackground(view, R.color.sfl_panel, 7)
            "palette-neutral" -> if (view is TextView) {
                applyPaletteBackground(view, R.color.sfl_chip_default, 5)
                view.setTextColor(selection.actual(view.context, PaletteColor.ON_NEUTRAL))
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) apply(view.getChildAt(i))
    }
    apply(root)
}

abstract class PaletteActivity : AppCompatActivity() {
    private lateinit var applied: PaletteSelection

    override fun attachBaseContext(newBase: Context) {
        applied = AppPalette.read(newBase)
        super.attachBaseContext(AppPalette.wrap(newBase, applied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (AppPalette.usesNativePalette(this)) theme.applyStyle(R.style.Theme_SflBrowser, true)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (applied.colors() != AppPalette.read(this).colors()) recreate()
    }
}

/** Android 8–10 (or an unavailable resource loader): recolor native views without touching WebView. */
internal object LegacyPalette {
    fun apply(root: View) {
        val context = root.context
        if (AppPalette.usesNativePalette(context)) return
        val colors = AppPalette.read(context).colors()
        if (colors.isEmpty()) return
        val original = PaletteColor.entries.associateWith { context.applicationContext.getColor(it.res) }
        val byColor = original.entries.groupBy({ it.value }, { it.key })
        fun mapped(color: Int, preferred: PaletteColor? = null): Int {
            val candidates = byColor[color].orEmpty()
            val matching = preferred?.takeIf { it in candidates } ?: candidates.singleOrNull()
            return colors[matching] ?: color
        }
        fun recolor(drawable: android.graphics.drawable.Drawable?, preferred: PaletteColor? = null) {
            when (drawable) {
                is ColorDrawable -> drawable.color = mapped(drawable.color, preferred)
                is GradientDrawable -> {
                    drawable.color?.defaultColor?.let { color -> drawable.setColor(mapped(color, preferred)) }
                    drawable.colors?.let { old -> drawable.colors = old.map { mapped(it, preferred) }.toIntArray() }
                }
                is LayerDrawable -> for (i in 0 until drawable.numberOfLayers) recolor(drawable.getDrawable(i), preferred)
            }
        }
        fun visit(view: View) {
            if (view is WebView) return
            recolor(view.background?.mutate(), if (view is EditText) PaletteColor.FIELD else null)
            val fg = view.backgroundTintList
            fg?.let {
                val color = mapped(it.defaultColor)
                if (color != it.defaultColor) view.backgroundTintList = ColorStateList.valueOf(color)
            }
            if (view is TextView) {
                val role = when {
                    view is EditText -> PaletteColor.FIELD_TEXT
                    view.currentTextColor == original[PaletteColor.TEXT] -> PaletteColor.TEXT
                    view.currentTextColor == original[PaletteColor.CHROME_TEXT] -> PaletteColor.CHROME_TEXT
                    view.currentTextColor == original[PaletteColor.POPUP_TEXT] -> PaletteColor.POPUP_TEXT
                    else -> null
                }
                val text = mapped(view.currentTextColor, role)
                if (view.currentTextColor != text) view.setTextColor(text)
                if (view is EditText) view.setHintTextColor(mapped(view.currentHintTextColor, PaletteColor.FIELD_TEXT))
            }
            if (view is ImageView) view.imageTintList?.let {
                val tint = mapped(it.defaultColor, PaletteColor.CHROME_TEXT)
                if (tint != it.defaultColor) view.imageTintList = ColorStateList.valueOf(tint)
            }
            if (view is ProgressBar) view.progressTintList?.let {
                val tint = mapped(it.defaultColor, PaletteColor.ACCENT)
                if (tint != it.defaultColor) view.progressTintList = ColorStateList.valueOf(tint)
            }
            if (view is TabLayout) {
                view.setSelectedTabIndicatorColor(colors[PaletteColor.ACCENT] ?: original.getValue(PaletteColor.ACCENT))
                view.setTabTextColors(
                    colors[PaletteColor.CHROME_TEXT] ?: original.getValue(PaletteColor.CHROME_TEXT),
                    colors[PaletteColor.ACCENT] ?: original.getValue(PaletteColor.ACCENT),
                )
            }
            if (view is MaterialSwitch) {
                val accent = colors[PaletteColor.ACCENT] ?: original.getValue(PaletteColor.ACCENT)
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                view.trackTintList = ColorStateList(states, intArrayOf(accent,
                    colors[PaletteColor.BORDER] ?: original.getValue(PaletteColor.BORDER)))
                view.thumbTintList = ColorStateList(states, intArrayOf(
                    colors[PaletteColor.ON_ACCENT] ?: original.getValue(PaletteColor.ON_ACCENT),
                    colors[PaletteColor.CHROME_TEXT] ?: original.getValue(PaletteColor.CHROME_TEXT)))
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
    }
}
