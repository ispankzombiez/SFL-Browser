package com.sfl.browser.overview

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ProgressBar
import android.content.res.ColorStateList
import com.sfl.browser.R
import com.sfl.browser.data.Prefs
import com.sfl.browser.notify.FarmDay
import com.sfl.browser.notify.farmObjects
import com.sfl.browser.ui.dp

/**
 * The farm's timers grouped into sections — Crops, Fruit Patches, the Bakery,
 * and so on — the way sfl-overview.com's dashboard lays them out.
 *
 * Same-named items finishing within the configured grouping window share a
 * row. Each row expands to show the individual timers.
 */
class OverviewActivity : TimerScreenActivity() {

    override val titleRes = R.string.overview_title
    override val subtitleRes = R.string.overview_subtitle
    override val emptyRes = R.string.empty_overview

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.overview_title)
    }

    override fun render() {
        container.removeAllViews()
        val now = System.currentTimeMillis()
        val details = Prefs.farmDetails(this)
        val day = details.optJSONArray("daily")
            .takeIf { details.optLong("dailyDay", Long.MIN_VALUE) == FarmDay.index(now) }
        val daily = day.farmObjects().toList()
        val pendingTimers = timers.filter(FarmDay::isDailyTask)
        val pending = daily.filterNot { it.optBoolean("done") }
        if (pendingTimers.isNotEmpty() || pending.isNotEmpty()) {
            val rows = section(getString(R.string.farm_tasks_pending))
            pendingTimers.forEach { addRow(rows, it) }
            pending.forEach { statusRow(rows, it.optString("name"), dailyDetail(it.optString("group")),
                R.string.timer_not_done, false) }
        }

        val projects = details.optJSONArray("projects").farmObjects().toList()
        if (projects.isNotEmpty()) {
            val rows = section(getString(R.string.alert_village_projects))
            for (project in projects) {
                val goal = project.getInt("goal")
                val cheers = project.getInt("cheers")
                val count = getString(R.string.village_cheers_count, cheers, goal)
                statusRow(rows, project.getString("name"), count)
                val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = goal
                    progress = cheers.coerceIn(0, goal)
                    progressTintList = ColorStateList.valueOf(getColor(R.color.sfl_chip_ready))
                    contentDescription = count
                }
                rows.addView(bar, LinearLayout.LayoutParams(-1, dp(14)).apply {
                    marginStart = dp(12)
                    marginEnd = dp(12)
                    bottomMargin = dp(8)
                })
            }
        }
        val pets = details.optJSONArray("pets").farmObjects().toList()
        if (pets.isNotEmpty()) {
            val rows = section(getString(R.string.farm_pet_satiation))
            for (pet in pets.sortedBy { it.optLong("neglectAt") }) {
                val at = pet.optLong("neglectAt")
                val neglected = now >= at
                val detail = if (neglected || at == Long.MAX_VALUE) "" else
                    getString(R.string.farm_pet_neglect_at, TimerUi.formatRemaining(at, now))
                statusRow(rows, pet.optString("name"), detail,
                    if (neglected) R.string.farm_pet_neglected else R.string.farm_pet_satiated, !neglected)
            }
        }

        val sections = timers.filterNot(FarmDay::isDailyTask)
            .groupBy { TimerUi.categoryOf(this, it) }
            .entries
            .sortedWith(compareBy({ it.key.order }, { it.key.title.lowercase() }))

        for ((category, items) in sections) {
            val panel = LayoutInflater.from(this).inflate(R.layout.view_timer_panel, container, false)
            if (items.any(TimerUi::isQueueTimer)) {
                bindQueueSection(panel, category, TimerUi.queueItems(items))
            } else {
                panel.findViewById<TextView>(R.id.txtSectionTitle).text = "${category.title} · ${items.size}"
                addGroupedRows(panel.findViewById<LinearLayout>(R.id.rows), items)
            }

            container.addView(panel)
        }
        val completed = daily.filter { it.optBoolean("done") }
        if (completed.isNotEmpty()) {
            val rows = section(getString(R.string.farm_tasks_completed))
            completed.forEach { statusRow(rows, it.optString("name"), dailyDetail(it.optString("group")),
                R.string.farm_status_done) }
        }
        if (container.childCount == 0) addEmpty()
    }

    private fun dailyDetail(group: String): String = when (group) {
        "kitchen" -> getString(R.string.farm_faction_kitchen)
        "pet" -> getString(R.string.farm_faction_pet)
        else -> ""
    }
}
