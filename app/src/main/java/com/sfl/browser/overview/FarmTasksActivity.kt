package com.sfl.browser.overview

import com.sfl.browser.R
import com.sfl.browser.data.Prefs
import com.sfl.browser.notify.farmObjects

class FarmTasksActivity : TimerScreenActivity() {
    override val titleRes = R.string.farm_tasks_title
    override val subtitleRes = R.string.farm_tasks_subtitle
    override val emptyRes = R.string.farm_tasks_empty

    override fun render() {
        container.removeAllViews()
        val tasks = Prefs.farmDetails(this).optJSONArray("tasks").farmObjects().toList()
        if (tasks.isEmpty()) {
            addEmpty()
            return
        }
        for (done in listOf(false, true)) {
            val items = tasks.filter { it.optBoolean("done") == done }
            if (items.isEmpty()) continue
            val body = section(getString(if (done) R.string.farm_tasks_completed else R.string.farm_tasks_pending))
            for ((group, label) in listOf(
                "deliveries" to R.string.farm_task_deliveries, "chores" to R.string.farm_task_chores,
                "bounties" to R.string.farm_task_bounties, "animalBounties" to R.string.farm_task_animal_bounties,
            )) {
                for (task in items.filter { it.optString("group") == group }) {
                    val name = task.optString("name")
                    val title = if (task.has("level")) getString(R.string.farm_task_animal_level, name, task.optInt("level"))
                        else name
                    val detail = listOf(getString(label), task.optString("detail"))
                        .filter(String::isNotBlank).joinToString(" · ")
                    statusRow(body, title, detail, if (done) R.string.farm_status_done else R.string.timer_not_done, done)
                }
            }
        }
    }
}
