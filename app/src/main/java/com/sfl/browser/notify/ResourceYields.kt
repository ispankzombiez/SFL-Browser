package com.sfl.browser.notify

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/** Predictions use the supplied live ready times and do not modify the farm snapshot. */
internal object ResourceYields {
    data class Node(val group: String, val id: String, val state: JSONObject, val readyAt: Long)

    private data class Kind(val defaultName: String, val item: String, val duration: Long, val ids: Map<String, Int>)
    private val kinds = mapOf(
        "trees" to Kind("Tree", "Wood", 7_200_000L, mapOf("Tree" to 618, "Ancient Tree" to 2702, "Sacred Tree" to 2703)),
        "stones" to Kind("Stone Rock", "Stone", 14_400_000L, mapOf("Stone Rock" to 619, "Fused Stone Rock" to 2700, "Reinforced Stone Rock" to 2701)),
        "iron" to Kind("Iron Rock", "Iron", 28_800_000L, mapOf("Iron Rock" to 620, "Refined Iron Rock" to 2704, "Tempered Iron Rock" to 2705)),
        "gold" to Kind("Gold Rock", "Gold", 86_400_000L, mapOf("Gold Rock" to 621, "Pure Gold Rock" to 2706, "Prime Gold Rock" to 2707)),
    )
    private val volcanoIslands = setOf("volcano", "swamp", "spooky", "crystal", "galaxy", "marble")
    private val knownIslands = volcanoIslands + setOf("basic", "spring", "desert")

    fun legacyReadyAt(group: String, anchor: Long): Long =
        kinds[group]?.let { anchor + it.duration } ?: 0L

    fun predict(farm: YieldFarm, farmId: Long?, nodes: List<Node>): Map<String, Map<String, Double?>> {
        val result = mutableMapOf<String, MutableMap<String, Double?>>()
        val groups = nodes.filter { it.group in kinds }.groupBy {
            val kind = kinds.getValue(it.group)
            it.group to it.state.optString("name").ifEmpty { kind.defaultName }
        }
        for ((key, entries) in groups) {
            val (group, name) = key
            val kind = kinds.getValue(group)
            val activity = farm.farm.optJSONObject("farmActivity")
            val activityKey = if (group == "trees") "${if (name == "Tree") "Basic Tree" else name} Chopped" else "$name Mined"
            var counter = activity?.optInt(activityKey) ?: 0
            val area = AreaState(farm)
            for (entry in entries.sortedBy { it.readyAt }) {
                val resource = entry.state.optJSONObject(if (group == "trees") "wood" else "stone")
                val amount = if (resource?.has("amount") == true && !resource.isNull("amount")) {
                    FarmYield.stored(resource)
                } else {
                    val id = kind.ids[name]
                    val roll = if (id != null && farmId != null && farmId > 0 && activity != null)
                        YieldRoll.forItem(farmId, id, counter) else null
                    val currentCounter = counter++
                    if (resource == null || roll == null || farm.skills == null || entry.readyAt <= 0) null
                    else calculate(farm, entry, kind, name, resource, roll, currentCounter, area)
                }
                result.getOrPut(group) { mutableMapOf() }[entry.id] = amount
            }
        }
        return result
    }

    private fun calculate(
        farm: YieldFarm, entry: Node, kind: Kind, name: String, resource: JSONObject,
        roll: YieldRoll, counter: Int, area: AreaState,
    ): Double? {
        val node = entry.state
        val multiplier = if (node.has("multiplier")) node.optDouble("multiplier") else 1.0
        if (!multiplier.isFinite() || multiplier < 0) return null
        val tier = node.optInt("tier", 1)
        if (tier !in 1..3) return null
        val at = maxOf(farm.now, entry.readyAt)
        val inventory = farm.farm.optJSONObject("inventory") ?: return null
        fun has(name: String) = inventory.optDouble(name, 0.0) > 0.0
        fun built(name: String) = farm.built(name)
        fun ranked(name: String, a: Double, b: Double, c: Double) = farm.ranked(name, a, b, c)
        var amount = 1.0
        if (kind.item == "Wood") {
            if (listOf("Woody the Beaver", "Apprentice Beaver", "Foreman Beaver").any(::built)) amount *= 1.2
            if (has("Discord Mod")) amount *= 1.35
            if (has("Lumberjack")) amount *= 1.1
            val chance = ranked("Tough Tree", 10.0, 20.0, 30.0)
            if (chance > 0 && roll.hit("Tough Tree", chance)) amount *= 3
            amount += ranked("Lumberjack's Extra", 0.1, 0.15, 0.2)
            if (built("Wood Nymph Wendy")) amount += 0.2
            if (built("Tiki Totem")) amount += 0.1
            if (built("Squirrel")) amount += 0.1
            if (shield(farm)) amount += 0.25
            // The first eight basic-tree rolls have tutorial outcomes.
            val native = if (name == "Tree" && counter in 0..7) counter >= 6 else roll.hit("Native", 20.0)
            if (native) amount += 1
            if (farm.temporary("Legendary Shrine", 1, at)) amount += 1
            amount += farm.bestBud { type, stem ->
                (if (type == "Woodlands") 0.2 else 0.0) + when (stem) {
                    "Acorn Hat" -> 0.1
                    "Tree Hat" -> 0.2
                    else -> 0.0
                }
            }
        } else {
            val island = farm.farm.optJSONObject("island")?.optString("type") ?: return null
            if (island !in knownIslands) return null
            when (kind.item) {
                "Stone" -> {
                    if (built("Rock Golem") && roll.hit("Rock Golem", 10.0)) amount += 2
                    if (has("Prospector")) amount += 0.2
                    if (built("Tunnel Mole")) amount += 0.25
                    if (built("Stone Beetle")) amount += 0.1
                    amount += ranked("Rock'N'Roll", 0.1, 0.15, 0.2)
                    amount += ranked("Rocky Favor", 1.0, 1.4, 1.8)
                    amount -= ranked("Ferrous Favor", 0.5, 0.6, 0.7)
                }
                "Iron" -> {
                    if (built("Rocky the Mole")) amount += 0.25
                    if (built("Radiant Ray")) amount += 0.1
                    if (built("Iron Idol")) amount += 1
                    if (built("Iron Beetle")) amount += 0.1
                    amount += ranked("Iron Bumpkin", 0.1, 0.15, 0.2)
                    amount -= ranked("Rocky Favor", 0.5, 0.6, 0.7)
                    amount += ranked("Ferrous Favor", 1.0, 1.5, 2.0)
                }
                "Gold" -> {
                    if (has("Gold Rush")) amount += 0.5
                    amount += ranked("Golden Touch", 0.5, 0.75, 1.0)
                    if (built("Nugget")) amount += 0.25
                    if (built("Gilded Swordfish")) amount += 0.1
                    if (built("Gold Beetle")) amount += 0.1
                }
            }
            if (roll.hit("Native", 20.0)) amount += 1
            val minedAt = resource.optLong("minedAt")
            val cooldown = if (resource.has("baseDurationMs")) entry.readyAt - minedAt
                else kind.duration - resource.optLong("boostedTime")
            if (cooldown < 0) return null
            amount += area.bonus("Emerald Turtle", node, cooldown, at, 0.5)
            if (kind.item == "Stone") amount += area.bonus("Tin Turtle", node, cooldown, at, 0.1)
            if (shield(farm)) amount += 0.25
            if (kind.item == "Stone" && farm.temporary("Legendary Shrine", 1, at)) amount += 1
            amount += farm.bestBud { type, stem ->
                (if (type == "Cave") 0.2 else 0.0) +
                    (if (stem == "Diamond Gem" || stem == when (kind.item) {
                        "Stone" -> "Ruby Gem"
                        "Iron" -> "Miner Hat"
                        else -> "Gold Gem"
                    }) 0.2 else 0.0)
            }
            if (island in volcanoIslands) amount += 0.1
        }
        amount = amount * multiplier + when (tier) { 2 -> 0.5; 3 -> 2.5; else -> 0.0 }
        return BigDecimal.valueOf(amount).setScale(4, RoundingMode.HALF_UP).toDouble()
    }

    private fun shield(farm: YieldFarm): Boolean {
        val shield = when (farm.farm.optJSONObject("faction")?.optString("name")) {
            "bumpkins" -> "Bumpkin Shield"
            "goblins" -> "Goblin Shield"
            "nightshades" -> "Nightshade Shield"
            "sunflorians" -> "Sunflorian Shield"
            else -> return false
        }
        return shield in farm.equipped
    }

    private class AreaState(private val farm: YieldFarm) {
        private val used = mutableMapOf<Triple<String, Int, Int>, Long>()
        fun bonus(name: String, node: JSONObject, cooldown: Long, at: Long, value: Double): Double {
            val instances = farm.farm.optJSONObject("collectibles")?.optJSONArray(name) ?: return 0.0
            val anyReady = (0 until instances.length()).any {
                val instance = instances.optJSONObject(it) ?: return@any false
                if (instance.optJSONObject("coordinates") == null) return@any false
                val readyAt = instance.optLong("readyAt")
                farm.checkAgainAt(readyAt)
                readyAt <= farm.now
            }
            if (!anyReady || !node.has("x") || !node.has("y")) return 0.0
            val pos = instances.optJSONObject(0)?.optJSONObject("coordinates") ?: return 0.0
            val dx = node.optInt("x") - pos.optInt("x")
            val dy = node.optInt("y") - pos.optInt("y")
            if (abs(dx) > 1 || abs(dy) > 1 || (dx == 0 && dy == 0)) return 0.0
            val key = Triple(name, dx, dy)
            val last = used[key] ?: farm.farm.optJSONObject("aoe")?.optJSONObject(name)
                ?.optJSONObject(dx.toString())?.optLong(dy.toString()) ?: 0L
            if (at - last < cooldown) {
                farm.checkAgainAt(last + cooldown)
                return 0.0
            }
            if (key in used) farm.checkAgainAt(at - cooldown + 1)
            used[key] = at
            return value
        }
    }
}
