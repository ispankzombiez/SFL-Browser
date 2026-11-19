package com.sfl.browser.notify

import org.json.JSONObject
import java.time.Instant
import kotlin.math.ceil

/** Speed windows add growth only while active; baseDurationMs already includes planting-time modifiers. */
internal class CropGrowth(private val farm: JSONObject, private val clock: FarmTimerClock? = null) {
    private data class Window(val from: Long, val to: Long, val rate: Double)

    private val history = farm.optJSONObject("boostHistory")
    private val home = farm.optJSONObject("home")
    private val interior = farm.optJSONObject("interior")
    private val locations = listOfNotNull(
        farm.optJSONObject("collectibles"),
        home?.optJSONObject("collectibles"),
        interior?.optJSONObject("ground")?.optJSONObject("collectibles"),
        interior?.optJSONObject("level_one")?.optJSONObject("collectibles"),
    )
    private val speedBoosts = (farm.optJSONObject("inventory")?.optDouble("Beta Pass") ?: 0.0) > 0
    private val totems by lazy {
        merge(collectible("Super Totem", 7 * DAY, 2.0) +
            collectible("Time Warp Totem", (if (speedBoosts) 4 else 2) * HOUR, 2.0))
    }
    private val harvestHourglass by lazy {
        collectible("Harvest Hourglass", (if (speedBoosts) 9 else 6) * HOUR, 1.35)
    }
    private val orchardHourglass by lazy {
        collectible("Orchard Hourglass", (if (speedBoosts) 8 else 6) * HOUR, 1.35)
    }
    private val plotWindows by lazy {
        val sunshower = sunshower()
        collectible("Sparrow Shrine", 7 * DAY, 1.35) + harvestHourglass +
            totems + powerHour() + sunshower + guardians(sunshower)
    }
    private val greenhouseWindows by lazy {
        totems + collectible("Tortoise Shrine", 7 * DAY, 1.5)
    }
    private val machineWindows by lazy {
        collectible("Tortoise Shrine", 7 * DAY, 10.0 / 9)
    }
    private val badgerWindows by lazy { collectible("Badger Shrine", 7 * DAY, 1.35) }
    private val moleWindows by lazy { collectible("Mole Shrine", 7 * DAY, 1.35) }
    private val timberWindows by lazy { totems + badgerWindows + collectible("Timber Hourglass", 4 * HOUR, 1.35) }
    private val oreWindows by lazy { totems + collectible("Ore Hourglass", (if (speedBoosts) 5 else 3) * HOUR, 2.0) }
    private val stoneWindows by lazy { oreWindows + badgerWindows }
    private val metalWindows by lazy { oreWindows + moleWindows }

    fun resourcePredictionReadyAt(group: String, anchor: Long, duration: Long): Long =
        readyAt(anchor, duration, when (group) {
            "trees" -> timberWindows
            "stones" -> stoneWindows
            "iron", "gold" -> metalWindows
            else -> emptyList()
        })

    fun machinePackReadyAt(start: Long, work: Long): Long =
        readyAt(start, work, machineWindows)

    private val bantamWindows by lazy { collectible("Bantam Shrine", 7 * DAY, 1.35) }
    private val collieWindows by lazy { collectible("Collie Shrine", 7 * DAY, 1.35) }

    fun animalReadyAt(animal: JSONObject): Long {
        if (!animal.has("baseDurationMs")) return animal.optLong("awakeAt")
        val windows = when (animal.optString("type")) {
            "Chicken" -> bantamWindows
            "Cow", "Sheep" -> collieWindows
            else -> emptyList()
        }
        return readyAt(animal.optLong("asleepAt"), animal.optLong("baseDurationMs"), windows)
    }

    fun checkAgainAt(vararg times: Long) {
        clock?.checkAgainAt(*times)
    }

    fun isBuilt(name: String, now: Long, includePetHouse: Boolean = false): Boolean {
        val petPlacements = if (includePetHouse) {
            val array = farm.optJSONObject("petHouse")?.optJSONObject("pets")?.optJSONArray(name)
            (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
        } else emptyList()
        return (placements(name) + petPlacements).any {
            if (it.optJSONObject("coordinates") == null || it.optBoolean("used")) return@any false
            val readyAt = it.optLong("readyAt")
            if (readyAt > now) checkAgainAt(readyAt)
            readyAt <= now
        }
    }

    fun hasActiveStagShrine(now: Long): Boolean =
        hasTemporaryCollectible("Stag Shrine", 7 * DAY, now)

    fun hasTemporaryCollectible(name: String, duration: Long, now: Long): Boolean =
        placements(name).any {
            val createdAt = it.optLong("createdAt")
            if (createdAt <= 0) return@any false
            val endsAt = createdAt + duration + it.optLong("extendedMs")
            if (createdAt > now) checkAgainAt(createdAt)
            if (endsAt > now) checkAgainAt(endsAt)
            createdAt <= now && endsAt > now
        }

    fun plotReadyAt(crop: JSONObject, plot: JSONObject): Long =
        readyAt(crop, plotWindows, fertiliser(plot, 2.0, "Rapid Root", "Sproutroot Surprise"))

    fun greenhouseReadyAt(plant: JSONObject, pot: JSONObject): Long {
        val hourglass = when (plant.optString("name")) {
            "Rice", "Olive" -> harvestHourglass
            "Grape" -> orchardHourglass
            else -> emptyList()
        }
        return readyAt(plant, greenhouseWindows + hourglass, fertiliser(pot, 1.25, "Greenhouse Glow"))
    }

    private fun collectible(name: String, duration: Long, rate: Double): List<Window> =
        merge(past(name, rate) + placements(name).mapNotNull { item ->
            if (!item.has("createdAt") || item.isNull("createdAt")) return@mapNotNull null
            val from = item.optLong("createdAt")
            val expiry = from + duration + item.optLong("extendedMs")
            Window(from, minOf(expiry, item.optLong("removedAt", expiry)), rate)
        })

    private fun placements(name: String): List<JSONObject> = locations.flatMap { location ->
        val items = location.optJSONArray(name) ?: return@flatMap emptyList()
        (0 until items.length()).mapNotNull(items::optJSONObject)
    }

    private fun past(name: String, rate: Double): List<Window> {
        val items = history?.optJSONArray(name) ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            Window(item.optLong("from"), item.optLong("to"), rate).takeIf { it.to > it.from }
        }
    }

    private fun powerHour(): List<Window> {
        val buff = farm.optJSONObject("buffs")?.optJSONObject("Power hour")
        val start = buff?.optLong("startedAt", -1L) ?: -1L
        val archived = past("Power hour", 2.0).filter { it.from != start }
        if (buff == null || start < 0) return merge(archived)
        return merge(archived + Window(start, start + buff.optLong("durationMS"), 2.0))
    }

    private fun sunshower(): List<Window> {
        val archived = past("Sunshower", 2.0)
        val start = farm.optJSONObject("calendar")?.optJSONObject("sunshower")
            ?.optLong("startedAt", -1L) ?: -1L
        if (start < 0 || archived.any { it.from == start }) return archived
        return listOf(Window(start, (start / DAY + 1) * DAY, 2.0)) + archived
    }

    private fun guardians(sunshower: List<Window>): List<Window> = merge(sunshower.flatMap { rain ->
        val week = (rain.from - FIRST_SEASON_WEEK).coerceAtLeast(0) / (7 * DAY)
        val name = GUARDIANS[(week % GUARDIANS.size).toInt()]
        val placed = placements(name).filter { it.optJSONObject("coordinates") != null }
            .map { Window(it.optLong("placedAt"), Long.MAX_VALUE, 2.0) }
        (placed + past(name, 2.0)).map {
            Window(maxOf(it.from, rain.from), minOf(it.to, rain.to), 2.0)
        }
    })

    private fun fertiliser(container: JSONObject, rate: Double, vararg names: String): List<Window> {
        val item = container.optJSONObject("fertiliser") ?: return emptyList()
        val from = item.optLong("fertilisedAt", -1L)
        return if (from >= 0 && item.optString("name") in names) {
            listOf(Window(from, Long.MAX_VALUE, rate))
        } else emptyList()
    }

    // Called only for equivalent boosts: overlap counts once, not as stacking.
    private fun merge(windows: List<Window>): List<Window> {
        val merged = ArrayList<Window>()
        for (window in windows.filter { it.to > it.from }.sortedBy { it.from }) {
            val last = merged.lastOrNull()
            if (last != null && window.from <= last.to) {
                merged[merged.lastIndex] = last.copy(to = maxOf(last.to, window.to))
            } else {
                merged += window
            }
        }
        return merged
    }

    private fun readyAt(plant: JSONObject, global: List<Window>, local: List<Window>): Long {
        return readyAt(plant.optLong("plantedAt"), plant.optLong("baseDurationMs"), global + local)
    }

    private fun readyAt(start: Long, duration: Long, boosts: List<Window>): Long {
        if (start <= 0 || duration <= 0) return 0L // Keep the existing missing-duration policy.
        val windows = boosts.filter { it.to > start && it.from < start + duration }
        val boundaries = windows.flatMap { listOf(maxOf(start, it.from), it.to) }.toSortedSet()
        var cursor = start
        var remaining = duration.toDouble()
        for (end in boundaries) {
            if (end <= cursor) continue
            val rate = windows.fold(1.0) { speed, window ->
                if (window.from <= cursor && cursor < window.to) speed * window.rate else speed
            }
            val work = (end - cursor) * rate
            if (remaining <= work) return cursor + ceil(remaining / rate).toLong()
            remaining -= work
            cursor = end
        }
        return cursor + ceil(remaining).toLong()
    }

    companion object {
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        private val FIRST_SEASON_WEEK = Instant.parse("2024-12-16T00:00:00Z").toEpochMilli()
        private val GUARDIANS = listOf("Spring Guardian", "Summer Guardian", "Autumn Guardian", "Winter Guardian")
    }
}
