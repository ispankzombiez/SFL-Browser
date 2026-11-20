package com.sfl.browser.notify

import android.content.Context
import com.sfl.browser.R
import com.sfl.browser.data.Prefs
import org.json.JSONObject

internal object FarmPollAlerts {
    const val PROJECTS = "projects"
    const val SICK = "sick"
    const val MUSHROOMS = "mushrooms"

    internal fun reached(before: JSONObject?, after: JSONObject?): List<String> {
        if (before == null || after == null) return emptyList()
        return after.keys().asSequence().filter { after.optBoolean(it) && !before.optBoolean(it) }.toList()
    }

    internal fun newlySick(before: JSONObject?, after: JSONObject?): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for ((herd, animals) in after.farmEntries()) {
            val previous = before?.optJSONObject(herd) ?: continue
            for (id in animals.keys()) {
                val name = animals.optString(id)
                if (!previous.has(id) && name.isNotBlank()) counts[name] = (counts[name] ?: 0) + 1
            }
        }
        return counts
    }

    fun check(context: Context, farm: String, current: JSONObject, baseline: Boolean) {
        val previous = Prefs.replaceFarmPollMarks(context, farm, current) ?: return
        if (baseline) return
        if (Prefs.farmAlertEnabled(context, PROJECTS)) {
            for (name in reached(previous.optJSONObject(PROJECTS), current.optJSONObject(PROJECTS))) {
                Notifier.postFarmAlert(context, "project:$name",
                    context.getString(R.string.alert_project_reached, name),
                    context.getString(R.string.alert_village_projects))
            }
        }
        if (Prefs.farmAlertEnabled(context, SICK)) {
            val animals = newlySick(previous.optJSONObject(SICK), current.optJSONObject(SICK))
            if (animals.isNotEmpty()) Notifier.postFarmAlert(context, SICK,
                context.getString(R.string.alert_sick_animal),
                animals.entries.joinToString(", ") { (name, count) ->
                    context.getString(R.string.notif_item_amount, count.toString(), name)
                })
        }
        if (Prefs.farmAlertEnabled(context, MUSHROOMS)) {
            val regular = previous.has("spawnedAt") && current.optLong("spawnedAt") > previous.optLong("spawnedAt")
            val magic = (previous.has("spawnedAt") || previous.has("magicSpawnedAt")) &&
                current.optLong("magicSpawnedAt") > previous.optLong("magicSpawnedAt")
            if (regular || magic) {
                val message = if (magic && !regular) R.string.alert_magic_mushroom_message else R.string.alert_mushroom_message
                Notifier.postFarmAlert(context, MUSHROOMS, context.getString(R.string.alert_mushroom_spawn),
                    context.getString(message))
            }
        }
    }
}
