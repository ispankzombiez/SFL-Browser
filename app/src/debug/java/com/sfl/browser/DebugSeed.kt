package com.sfl.browser

import android.content.Context
import com.sfl.browser.data.ItemIcons
import com.sfl.browser.data.Prefs
import com.sfl.browser.notify.FarmTimer
import com.sfl.browser.notify.FarmTimers

/**
 * Debug-only fixture: a believable spread of timers so the Overview and
 * Timeline screens can be eyeballed on an emulator without an API key.
 *
 * Lives in `src/debug`, is never packaged into a release APK, and contains no
 * credentials — a refresh simply fails and the fixture stays on screen.
 */
object DebugSeed {

    private const val MARKER = "state.debugSeedDone"

    fun ensure(context: Context) {
        val sp = context.getSharedPreferences("sfl_prefs", Context.MODE_PRIVATE)
        if (sp.getBoolean(MARKER, false)) return
        sp.edit().putBoolean(MARKER, true).apply()

        val now = System.currentTimeMillis()
        val m = 60_000L
        val h = 60 * m

        fun icon(label: String) = ItemIcons.pathForName(context, label)

        val timers = listOf(
            // Ready and about to roll up: four ripe plots.
            FarmTimer("crop:1", "Kale", now - 40_000L, icon("Kale"), "Crop plot 1"),
            FarmTimer("crop:2", "Sunflower", now - 12_000L, icon("Sunflower"), "Crop plot 2"),
            FarmTimer("crop:3", "Carrot", now - 4_000L, icon("Carrot"), "Crop plot 3"),
            FarmTimer("crop:4", "Cauliflower", now, icon("Cauliflower"), "Crop plot 4"),
            // Waiting crops, spread across the status buckets.
            FarmTimer("crop:5", "Parsnip", now + 4 * m, icon("Parsnip"), "Crop plot 5"),
            FarmTimer("crop:6", "Kale", now + 12 * m, icon("Kale"), "Crop plot 6"),
            FarmTimer("crop:7", "Radish", now + 47 * m, icon("Radish"), "Crop plot 7"),
            // Other sections.
            FarmTimer("fruit:1", "Blueberry", now + 6 * m, icon("Blueberry"), "Fruit patch 1"),
            FarmTimer("fruit:2", "Orange", now + 3 * h, icon("Orange"), "Fruit patch 2"),
            FarmTimer("gh:1", "Rice", now + 22 * m, icon("Rice"), "Greenhouse pot 1"),
            FarmTimer("flower:1", "Blue Rose", now + 90 * m, icon("Blue Rose"), "Flower bed 1"),
            FarmTimer("tree:1", "Tree", now + 2 * h, icon("Tree"), "Tree 1"),
            FarmTimer("stones:1", "Stone", now + 5 * h, icon("Stone"), "Stone"),
            // Cooking: two buildings, so the dynamic grouping shows up.
            FarmTimer("craft:Bakery:0:1", "Bread", now + 8 * m, icon("Bread"), "Bakery"),
            FarmTimer("craft:Kitchen:0:1", "Boiled Eggs", now + 150 * m, icon("Boiled Eggs"), "Kitchen"),
            FarmTimer("trap:1", "Crab Trap", now + 25 * m, icon("Crab Trap"), "Crab trap 1"),
            FarmTimer(
                "collectible:Immortal Pear:0", "Immortal Pear",
                now + 26 * h, icon("Immortal Pear"), "Immortal Pear",
            ),
        )

        Prefs.setTimersJson(context, FarmTimers.toJson(timers))
        Prefs.setLastFetchedAt(context, now - 32_000L)
    }
}
