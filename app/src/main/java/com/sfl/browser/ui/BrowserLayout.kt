package com.sfl.browser.ui

import android.content.Context
import android.util.Log
import com.sfl.browser.R
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal enum class BrowserControl(val label: Int, val icon: Int = 0) {
    BACK(R.string.cd_back, R.drawable.ic_arrow_back),
    FORWARD(R.string.cd_forward, R.drawable.ic_arrow_forward),
    ADDRESS(R.string.browser_address),
    TABS(R.string.tab_switcher_title, R.drawable.ic_browser_tabs),
    OPTIONS(R.string.cd_menu, R.drawable.ic_menu),
    REFRESH(R.string.menu_refresh, android.R.drawable.ic_popup_sync),
    OVERVIEW(R.string.menu_overview, android.R.drawable.ic_menu_agenda),
    TIMELINE(R.string.menu_timeline, android.R.drawable.ic_menu_recent_history),
    SETTINGS(R.string.menu_settings, android.R.drawable.ic_menu_preferences),
}

internal enum class BrowserRow(val label: Int) {
    TOP(R.string.browser_row_top), TOP_SECOND(R.string.browser_row_top_second),
    BOTTOM(R.string.browser_row_bottom), BOTTOM_SECOND(R.string.browser_row_bottom_second),
    HIDDEN(R.string.browser_row_hidden),
}

internal enum class ControlSize(val label: Int, val buttonDp: Int, val iconDp: Int, val addressDp: Int, val heightDp: Int) {
    SMALL(R.string.browser_size_small, 48, 20, 112, 48),
    MEDIUM(R.string.browser_size_medium, 56, 24, 152, 52),
    LARGE(R.string.browser_size_large, 64, 30, 200, 60),
    FILL(R.string.browser_size_fill, 48, 20, 96, 48),
}

internal data class BrowserLayout(
    val rows: Map<BrowserRow, List<BrowserControl>>,
    val sizes: Map<BrowserControl, ControlSize> = emptyMap(),
) {
    fun size(control: BrowserControl) = sizes[control]
        ?: if (control == BrowserControl.ADDRESS) ControlSize.FILL else ControlSize.SMALL

    fun resize(control: BrowserControl, size: ControlSize): BrowserLayout =
        if (size == ControlSize.FILL && control != BrowserControl.ADDRESS) this
        else copy(sizes = sizes + (control to size))

    fun move(control: BrowserControl, row: BrowserRow, before: BrowserControl? = null): BrowserLayout {
        if (before == control) return this
        val updated = BrowserRow.entries.associateWith { rows[it].orEmpty().filterNot { item -> item == control } }
        val target = updated.getValue(row).toMutableList()
        val index = target.indexOf(before).takeIf { it >= 0 } ?: target.size
        target.add(index, control)
        return copy(rows = updated + (row to target))
    }

    fun minimumWidth(control: BrowserControl): Int =
        if (control == BrowserControl.ADDRESS) size(control).addressDp else size(control).buttonDp

    fun fittedRows(row: BrowserRow, availableDp: Int): List<List<BrowserControl>> {
        val fitted = mutableListOf<MutableList<BrowserControl>>()
        var used = 0
        for (control in rows[row].orEmpty()) {
            val width = minimumWidth(control)
            if (fitted.isEmpty() || used + width > availableDp) {
                fitted.add(mutableListOf())
                used = 0
            }
            fitted.last().add(control)
            used += width
        }
        return fitted
    }

    fun encode(): String = JSONObject().apply {
        BrowserRow.entries.forEach { row ->
            put(row.name, JSONArray(rows[row].orEmpty().map { it.name }))
        }
        put("sizes", JSONObject(sizes.mapKeys { it.key.name }.mapValues { it.value.name }))
    }.toString()

    companion object {
        val default = BrowserLayout(mapOf(
            BrowserRow.TOP to listOf(BrowserControl.BACK, BrowserControl.FORWARD, BrowserControl.ADDRESS,
                BrowserControl.TABS, BrowserControl.OPTIONS),
            BrowserRow.HIDDEN to listOf(BrowserControl.REFRESH, BrowserControl.OVERVIEW,
                BrowserControl.TIMELINE, BrowserControl.SETTINGS),
        ))

        fun decode(raw: String): BrowserLayout {
            val json = JSONObject(raw)
            val seen = mutableSetOf<BrowserControl>()
            val rows = BrowserRow.entries.associateWith { row ->
                val items = json.optJSONArray(row.name) ?: return@associateWith emptyList()
                (0 until items.length()).mapNotNull { i ->
                    BrowserControl.entries.find { it.name == items.optString(i) }?.takeIf(seen::add)
                }
            }
            val sizes = json.optJSONObject("sizes")
            return BrowserLayout(
                rows + (BrowserRow.HIDDEN to (rows.getValue(BrowserRow.HIDDEN) + BrowserControl.entries.filter { it !in seen })),
                BrowserControl.entries.mapNotNull { control ->
                    ControlSize.entries.find { it.name == sizes?.optString(control.name) &&
                        (it != ControlSize.FILL || control == BrowserControl.ADDRESS) }?.let { control to it }
                }.toMap(),
            )
        }

        fun read(context: Context): BrowserLayout {
            val stored = prefs(context).getString("layout", null) ?: return default
            return try {
                decode(stored)
            } catch (e: JSONException) {
                Log.w("BrowserLayout", "Invalid saved browser layout; using the default", e)
                default
            }
        }

        fun save(context: Context, layout: BrowserLayout) {
            prefs(context).edit().putString("layout", layout.encode()).apply()
        }

        private fun prefs(context: Context) = context.getSharedPreferences("browser_layout", Context.MODE_PRIVATE)
    }
}
