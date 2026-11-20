package com.sfl.browser.notify

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

internal fun JSONArray?.farmObjects(): Sequence<JSONObject> = sequence {
    val array = this@farmObjects ?: return@sequence
    for (i in 0 until array.length()) array.optJSONObject(i)?.let { yield(it) }
}

internal fun JSONObject?.farmEntries(): Sequence<Pair<String, JSONObject>> = sequence {
    val obj = this@farmEntries ?: return@sequence
    for (key in obj.keys()) obj.optJSONObject(key)?.let { yield(key to it) }
}

internal object FarmDetails {
    private val projectGoals = mapOf(
        "Big Orange" to 25, "Big Apple" to 50, "Big Banana" to 200,
        "Basic Cooking Pot" to 10, "Expert Cooking Pot" to 50, "Advanced Cooking Pot" to 100,
        "Farmer's Monument" to 100, "Woodcutter's Monument" to 1000, "Miner's Monument" to 10000,
        "Teamwork Monument" to 100, "Cornucopia" to 1000, "Poseidon's Throne" to 2000,
        "Crystal Altar" to 1000, "Ascension Monument" to 1000, "Duende Monument" to 1000,
    )
    private val animals = setOf("Chicken", "Cow", "Sheep", "Pig")

    fun extract(farm: JSONObject, now: Long): JSONObject = JSONObject()
        .put("projects", projects(farm))
        .put("tasks", tasks(farm))
        .put("pets", pets(farm, now))
        .put("dailyDay", FarmDay.index(now))
        .put("daily", daily(farm, now))
        .put("reminders", FarmReminders.extract(farm))

    private fun projects(farm: JSONObject): JSONArray {
        val social = farm.optJSONObject("socialFarming") ?: return JSONArray()
        val completed = social.optJSONArray("completedProjects")
        val claimed = (0 until (completed?.length() ?: 0)).map { completed?.optString(it) }.toSet()
        val interior = farm.optJSONObject("interior")
        val surfaces = listOf(farm, farm.optJSONObject("home"), interior?.optJSONObject("ground"),
            interior?.optJSONObject("level_one"))
        val results = JSONArray()
        for ((name, goal) in projectGoals) {
            val progress = social.optJSONObject("villageProjects")?.optJSONObject(name)
            val cheers = if (name in claimed) goal else progress?.optInt("cheers", -1) ?: -1
            if (cheers < 0) continue
            val placed = surfaces.any { surface ->
                surface?.optJSONObject("collectibles")?.optJSONArray(name).farmObjects()
                    .any { it.optJSONObject("coordinates") != null }
            }
            if (placed) results.put(JSONObject().put("name", name).put("cheers", cheers).put("goal", goal))
        }
        return results
    }

    private fun tasks(farm: JSONObject): JSONArray {
        val results = JSONArray()
        fun add(group: String, name: String, done: Boolean, detail: String = "") {
            if (name.isNotBlank()) {
                results.put(JSONObject().put("group", group).put("name", name).put("done", done).put("detail", detail))
            }
        }
        val orders = farm.optJSONObject("delivery")?.optJSONArray("orders")
        for (order in orders.farmObjects()) {
            add("deliveries", order.optString("from"), order.optLong("completedAt") > 0,
                items(order.optJSONObject("items")))
        }
        val choreBoard = farm.optJSONObject("choreBoard")?.optJSONObject("chores")
        for ((npc, chore) in choreBoard.farmEntries()) {
            add("chores", chore.optString("name"), chore.optLong("completedAt") > 0, npc)
        }
        val chores = farm.optJSONObject("chores")?.optJSONObject("chores").farmEntries().map { it.second }
        val kingdom = farm.optJSONObject("kingdomChores")?.optJSONArray("chores").farmObjects()
        for (chore in chores + kingdom) {
            if (chore.optLong("skippedAt") > 0) continue
            add("chores", chore.optString("description"), chore.optLong("completedAt") > 0)
        }
        val bounties = farm.optJSONObject("bounties")
        val sold = bounties?.optJSONArray("completed").farmObjects().map { it.optString("id") }.toSet()
        for (bounty in bounties?.optJSONArray("requests").farmObjects()) {
            val name = bounty.optString("name").takeIf(String::isNotBlank) ?: continue
            val group = if (name in animals && bounty.has("level")) "animalBounties" else "bounties"
            val details = listOf(
                bounty.optLong("coins").takeIf { it > 0 }?.let { "$it Coins" }.orEmpty(),
                items(bounty.optJSONObject("items")),
            ).filter(String::isNotBlank).joinToString(", ")
            add(group, name, bounty.optString("id").takeIf(String::isNotBlank) in sold, details)
            if (group == "animalBounties") (results.optJSONObject(results.length() - 1))
                ?.put("level", bounty.optInt("level"))
        }
        return results
    }

    private fun items(items: JSONObject?): String = items?.keys()?.asSequence()?.joinToString(", ") {
        "${items.optString(it)} $it"
    }.orEmpty()

    private fun pets(farm: JSONObject, now: Long): JSONArray {
        val results = JSONArray()
        val pets = farm.optJSONObject("pets")
        for (family in listOf("common", "nfts")) {
            for ((_, pet) in pets?.optJSONObject(family).farmEntries()) {
                val name = pet.optString("name").takeIf(String::isNotBlank) ?: continue
                val xp = pet.optDouble("experience")
                if (!xp.isFinite()) continue
                val fed = pet.optJSONObject("requests")?.optLong("fedAt", -1L) ?: -1L
                val caredAt = maxOf(fed, pet.optLong("cheeredAt", -1L))
                if (xp > 0 && caredAt !in 0..now) continue
                // The game uses UTC day counts, and neglect starts only after the limit is exceeded.
                val neglectAt = if (xp <= 0) Long.MAX_VALUE else
                    FarmDay.start(caredAt) + (if (family == "nfts") 8 else 4) * FarmDay.DAY_MS
                results.put(JSONObject().put("name", name).put("neglectAt", neglectAt))
            }
        }
        return results
    }

    private fun daily(farm: JSONObject, now: Long): JSONArray {
        val results = JSONArray()
        fun add(group: String, name: String, done: Boolean) {
            results.put(JSONObject().put("group", group).put("name", name).put("done", done))
        }
        val opened = farm.optJSONObject("pumpkinPlaza")?.optJSONObject("budBox")?.optLong("openedAt") ?: 0L
        if (opened in FarmDay.start(now)..now && opened > 0L) add("budBox", "Bud Box", true)
        val today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
        val weekday = today.dayOfWeek.value
        val monday = today.minusDays(weekday.toLong() - 1).toString()
        val faction = farm.optJSONObject("faction")
        for ((group, item) in listOf("kitchen" to "item", "pet" to "food")) {
            val data = faction?.optJSONObject(group)
            if (data?.optString("week") != monday) continue
            val contributionDays = if (group == "pet") 1..7 else weekday..weekday
            for (request in data.optJSONArray("requests").farmObjects()) {
                val name = request.optString(item).takeIf(String::isNotBlank) ?: continue
                val fulfilled = request.optJSONObject("dailyFulfilled") ?: continue
                add(group, name, contributionDays.any { fulfilled.optDouble(it.toString(), 0.0) > 0 })
            }
        }
        return results
    }

    fun pollState(farm: JSONObject, details: JSONObject, now: Long): JSONObject {
        val marks = JSONObject()
        if (farm.optJSONObject("socialFarming")?.optJSONObject("villageProjects") != null) {
            val projects = JSONObject()
            for (project in details.optJSONArray("projects").farmObjects()) {
                projects.put(project.getString("name"), project.getInt("cheers") >= project.getInt("goal"))
            }
            marks.put("projects", projects)
        }
        val sick = JSONObject()
        for (building in listOf("henHouse", "barn", "pigpen")) {
            val herd = farm.optJSONObject(building)?.optJSONObject("animals") ?: continue
            val states = JSONObject()
            for ((id, animal) in herd.farmEntries()) {
                if (animal.optString("state") == "sick") states.put(id, animal.optString("type"))
            }
            sick.put(building, states)
        }
        if (sick.length() > 0) marks.put("sick", sick)
        farm.optJSONObject("mushrooms")?.let { mushroom ->
            for (field in listOf("spawnedAt", "magicSpawnedAt")) {
                if (mushroom.has(field)) {
                    val at = mushroom.optLong(field)
                    if (at in 0..now) marks.put(field, at)
                }
            }
        }
        return marks
    }
}
