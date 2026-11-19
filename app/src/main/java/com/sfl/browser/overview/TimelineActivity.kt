package com.sfl.browser.overview

import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.sfl.browser.R
import com.sfl.browser.notify.FarmDay

/**
 * Farm timers and events, grouped like Overview and sorted by their next time —
 * so the list reads as "what do I collect next", soonest at the top.
 */
class TimelineActivity : TimerScreenActivity() {

    override val titleRes = R.string.timeline_title
    override val subtitleRes = R.string.timeline_subtitle
    override val emptyRes = R.string.empty_timeline
    override val includeEvents = true

    override fun render() {
        container.removeAllViews()
        val (dailyTasks, timed) = timers.partition(FarmDay::isDailyTask)
        val reset = TimerUi.dailyReset(getString(R.string.timeline_daily_reset), System.currentTimeMillis())
        val (queueTimers, otherTimers) = timed.partition(TimerUi::isQueueTimer)
        val (raffleStarts, groupable) = otherTimers.partition(TimerUi::isRaffleStart)
        val queues = queueTimers.groupBy { TimerUi.categoryOf(this, it) }.map { (category, items) ->
            category to TimerUi.queueItems(items)
        }
        // Queue sections (buildings, Crafting Box) sort by their *last* item —
        // that is when their one notification fires.
        val entries = (groupedRows(groupable).map { null to it } +
            (raffleStarts + reset).map { null to listOf(it) } + queues)
            .sortedBy { (category, items) ->
                if (category == null) items.first().readyAt else items.last().readyAt
            }

        val panel = LayoutInflater.from(this).inflate(R.layout.view_timer_panel, container, false)
        panel.findViewById<TextView>(R.id.txtSectionTitle).text =
            "${getString(R.string.timeline_title)} · ${dailyTasks.size + otherTimers.size + 1 + queues.sumOf { it.second.size }}"
        val rows = panel.findViewById<LinearLayout>(R.id.rows)
        dailyTasks.forEach { addRow(rows, it) }
        for ((category, items) in entries) {
            if (category == null) {
                val single = items.singleOrNull()
                if (single != null && TimerUi.isRaffleStart(single)) addRaffleStart(rows, single)
                else addTimerGroup(rows, items)
            } else {
                val queue = LayoutInflater.from(this).inflate(R.layout.view_timer_panel, rows, false)
                bindQueueSection(queue, category, items)
                rows.addView(queue)
            }
        }
        container.addView(panel)
    }
}
