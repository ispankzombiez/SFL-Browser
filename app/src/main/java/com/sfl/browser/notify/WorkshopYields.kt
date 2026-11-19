package com.sfl.browser.notify

import org.json.JSONObject

internal class WorkshopYields(private val yields: YieldFarm, private val farmId: Long?) {
    private data class Worm(val name: String, val itemId: Int, val amounts: List<Int>)
    private data class Spice(val itemId: Int, val batch: Int)

    private val activity = yields.farm.optJSONObject("farmActivity")
    private val counters = mutableMapOf<String, Int>()

    fun compost(building: String, stored: JSONObject): Map<String, Double>? {
        val products = FarmYield.readProducts(stored)?.toMutableMap() ?: return null
        val worm = worms.getValue(building)
        val id = farmId?.takeIf { it > 0 }
        val counter = nextCounter("${worm.name} Collected")
        if (id == null || counter == null || yields.skills == null) return products

        // Legacy batches stored worms with the fertilizer; the current game rolls at collection.
        val roll = YieldRoll.randomValue(id, worm.itemId, counter, "Native")
        val base = worm.amounts[(roll * worm.amounts.size).toInt()]
        val multiplier = if (yields.built("Straw House")) 3 else 1
        val gear = listOf("Bucket O' Worms", "Saw Fish").count { it in yields.equipped }
        val slug = if (yields.built("Deep Sea Slug")) 1 else 0
        val adjustment = gear + slug + yields.rank("Wormy Treat") +
            yields.ranked("Composting Overhaul", 2.0, 5.0, 8.0).toInt() -
            yields.ranked("Composting Revamp", 2.0, 3.0, 4.0).toInt()
        val amount = (base * multiplier + adjustment).coerceAtLeast(0)
        products.remove(worm.name)
        if (amount > 0) products[worm.name] = amount.toDouble()
        return products
    }

    fun spice(recipe: String, job: JSONObject): Double? {
        val output = spices[recipe] ?: return null
        val ager = when (val stamp = job.optJSONObject("skills")?.opt("Ager")) {
            true -> 1
            is Number -> stamp.toInt().coerceIn(0, 3)
            else -> 0
        }
        val counter = nextCounter("$recipe Spiced")
        val id = farmId?.takeIf { it > 0 }
        val roll = if (id != null && counter != null) YieldRoll.forItem(id, output.itemId, counter) else null
        val astrolabe = yields.built("Astrolabe")
        val refiner = if (recipe == "Refined Salt") yields.ranked("Refiner", 15.0, 25.0, 35.0) else 0.0
        if (roll == null && (astrolabe || refiner > 0)) return null

        var amount = output.batch * (1 + ager)
        if (astrolabe && roll?.hit("Astrolabe", 15.0) == true) amount *= 2
        if (refiner > 0 && roll?.hit("Refiner", refiner) == true) amount++
        if ("Salt Bottle Onesie" in yields.equipped) amount++
        return amount.toDouble()
    }

    private fun nextCounter(event: String): Int? {
        val history = activity ?: return null
        val counter = counters[event] ?: history.optInt(event)
        counters[event] = counter + 1
        return counter
    }

    companion object {
        private val worms = mapOf(
            "Compost Bin" to Worm("Earthworm", 626, listOf(2, 3, 3, 3, 4)),
            "Turbo Composter" to Worm("Grub", 627, listOf(2, 2, 2, 3, 3)),
            "Premium Composter" to Worm("Red Wiggler", 628, listOf(1, 1, 2, 2, 2, 3)),
        )
        private val spices = mapOf(
            "Refined Salt" to Spice(666, 1), "Salt Lick" to Spice(667, 5),
            "Honey Treat" to Spice(668, 5), "Truffle Seasoning" to Spice(3101, 5),
            "Spice Base" to Spice(669, 1), "Spiced Cheese" to Spice(670, 1),
        )
    }
}
