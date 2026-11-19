package com.sfl.browser.overview

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.sfl.browser.R

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
        if (timers.isEmpty()) {
            addEmpty()
            return
        }

        val sections = timers
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
    }

}
