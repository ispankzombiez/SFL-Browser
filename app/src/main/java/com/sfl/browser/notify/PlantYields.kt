package com.sfl.browser.notify

import org.json.JSONObject
import kotlin.math.round

/**
 * Predicts fruit-patch and greenhouse harvest amounts from the farm state.
 *
 * A null result means the supplied snapshot is insufficient to make a complete
 * prediction (for example, an enabled critical-yield effect without its seeded roll).
 */
internal object PlantYields {
    private val patchFruit = setOf(
        "Apple", "Blueberry", "Orange", "Banana", "Tomato", "Lemon",
        "Celestine", "Lunara", "Duskberry",
    )
    private val fullMoonFruit = setOf("Celestine", "Lunara", "Duskberry")
    private val commonFruitPicker = setOf("Apple", "Blueberry", "Orange", "Banana")
    private val citrusFruit = setOf("Tomato", "Lemon")
    private val guardianBySeason = mapOf(
        "spring" to "Spring Guardian",
        "summer" to "Summer Guardian",
        "autumn" to "Autumn Guardian",
        "winter" to "Winter Guardian",
    )

    fun fruit(
        fruit: JSONObject,
        fertiliser: String?,
        state: YieldFarm,
        roll: YieldRoll? = null,
        harvestAt: Long = state.now,
    ): Double? {
        FarmYield.stored(fruit)?.let { return it }
        if (state.skills == null) return null

        val name = fruit.optString("name")
        if (name !in patchFruit && name != "Grape") return null
        var amount = 1.0

        if (name in patchFruit) {
            val generousOrchard = state.rank("Generous Orchard")
            if (generousOrchard > 0) {
                val hit = roll?.hit(
                    "Generous Orchard",
                    doubleArrayOf(20.0, 30.0, 50.0)[generousOrchard - 1],
                ) ?: return null
                if (hit) amount += 1.0
            }

            if (name == "Apple" && state.built("Lady Bug")) amount += 0.25
            if (name == "Blueberry" && state.built("Black Bearry")) amount += 1.0
            if (name in fullMoonFruit && "Moon Hair" in state.equipped) amount += 0.5

            if (state.built("Macaw")) {
                amount += state.ranked("Loyal Macaw", 0.2, 0.25, 0.3)
                    .takeIf { it > 0.0 } ?: 0.1
            }
            if ("Camel Onesie" in state.equipped) amount += 0.1
            if (name in commonFruitPicker && "Fruit Picker Apron" in state.equipped) amount += 0.1
            amount += state.ranked("Fruitful Fumble", 0.1, 0.15, 0.2)
            if ("Fruitful Blend" == fertiliser || "Turbofruit Mix" == fertiliser) {
                val multiplier = state.ranked("Fruitful Bounty", 2.0, 3.0, 4.0)
                amount += 0.1 * if (multiplier > 0.0) multiplier else 1.0
            }
            if (name == "Banana" && "Banana Amulet" in state.equipped) amount += 0.5
            if (name == "Banana" && state.built("Banana Chicken")) amount += 0.1
            if (name == "Lemon") {
                if (state.built("Lemon Shark")) amount += 0.2
                if ("Lemon Shield" in state.equipped) amount += 1.0
                if (state.built("Reveling Lemon")) amount += 0.25
            }
            if (name == "Tomato" && state.built("Tomato Bombard")) amount += 1.0
        } else {
            if (state.built("Vinny")) amount += 0.25
            if (state.built("Grape Granny")) amount += 1.0
            if ("Grape Pants" in state.equipped) amount += 0.2
        }

        if (state.factionQuiver()) amount += 0.25
        amount += state.bestBud { type, stem ->
            (if (type == "Beach") 0.2 else 0.0) +
                (if (stem == "Banana" || stem == "Apple Head") 0.2 else 0.0)
        }
        if (state.temporary("Legendary Shrine", 1, harvestAt)) amount += 1.0
        amount += harvestCalendarBonus(state) ?: return null
        if (state.rank("Zesty Vibes") > 0 && name in patchFruit) {
            val rank = state.rank("Zesty Vibes") - 1
            amount += if (name in citrusFruit) {
                doubleArrayOf(1.0, 1.5, 2.0)[rank]
            } else {
                -doubleArrayOf(0.25, 0.4, 0.5)[rank]
            }
        }

        return amount.roundYield()
    }

    fun greenhouse(
        plant: JSONObject,
        fertiliser: String?,
        state: YieldFarm,
        roll: YieldRoll? = null,
        harvestAt: Long = state.now,
    ): Double? {
        val name = plant.optString("name")
        if (name != "Grape" && name != "Rice" && name != "Olive") return null

        val goodie = if (fertiliser == "Greenhouse Goodie") 0.2 else 0.0
        val stored = FarmYield.stored(plant)
        // A stored nonzero amount bypasses all yield rules except the Goodie.
        if (stored != null && stored != 0.0) return (stored + goodie).roundYield()
        if (state.skills == null) return null

        var amount = if (name == "Grape") {
            val basePlant = if (stored == 0.0) JSONObject().put("name", name) else plant
            fruit(basePlant, fertiliser = null, state = state, roll = roll, harvestAt = harvestAt) ?: return null
        } else {
            greenhouseCropBase(name, state, roll, harvestAt) ?: return null
        }

        if (state.rank("Greenhouse Gamble") > 0) {
            val rank = state.rank("Greenhouse Gamble") - 1
            val hit = roll?.hit(
                "Greenhouse Gamble",
                doubleArrayOf(30.0, 40.0, 50.0)[rank],
            ) ?: return null
            if (hit) amount += 1.0
        }
        if (state.built("Pharaoh Gnome")) amount += 2.0
        if (name == "Grape" && "Antique Dress" in state.equipped) {
            val hit = roll?.hit("Antique Dress", 10.0) ?: return null
            if (hit) amount += 10.0
        }
        amount += state.ranked("Glass Room", 0.1, 0.15, 0.2)
        amount += state.ranked("Seeded Bounty", 0.5, 0.75, 1.0)
        amount += state.ranked("Greasy Plants", 1.0, 1.5, 2.0)
        // Zero routes through live calculation, then the explicit stored-field
        // check in the harvest action adds a second Goodie bonus.
        amount += if (stored == 0.0) 2 * goodie else goodie

        return amount.roundYield()
    }

    private fun greenhouseCropBase(name: String, state: YieldFarm, roll: YieldRoll?, harvestAt: Long): Double? {
        var amount = 1.0
        if ("Green Amulet" in state.equipped) {
            val hit = roll?.hit("Green Amulet", 10.0) ?: return null
            if (hit) amount *= 10.0
        }
        if (state.built("Scarecrow") || state.built("Kuebiko")) amount *= 1.2
        if ((state.farm.optJSONObject("inventory")?.optDouble("Coder") ?: 0.0) >= 1.0) {
            amount *= 1.2
        }

        val buff = state.farm.optJSONObject("buffs")?.optJSONObject("Power hour")
        val startedAt = buff?.optLong("startedAt") ?: 0L
        val duration = buff?.optLong("durationMS") ?: 0L
        if (startedAt > 0 && duration > 0 && harvestAt < startedAt + duration) {
            state.checkAgainAt(startedAt, startedAt + duration)
        }
        if (startedAt > 0 && duration > 0 && harvestAt in startedAt until startedAt + duration) {
            amount += 0.2
        }

        if ("Infernal Pitchfork" in state.equipped) amount += 3.0
        if (state.factionQuiver()) amount += 0.25
        if (state.temporary("Legendary Shrine", 1, harvestAt)) amount += 1.0
        if (name == "Rice" && state.built("Hoot")) amount += 0.5
        amount += state.bestBud { _, stem -> if (stem == "3 Leaf Clover") 0.5 else 0.0 }
        if (activeYieldEvent(state) == "insectPlague" &&
            state.farm.optJSONObject("calendar")?.optJSONObject("insectPlague")?.optBoolean("protected") != true) {
            amount *= 0.5
        }
        amount += harvestCalendarBonus(state) ?: return null

        if (name == "Olive") {
            if ("Olive Royalty Shirt" in state.equipped) amount += 0.25
            if ("Olive Shield" in state.equipped) amount += 1.0
        } else {
            if ("Non La Hat" in state.equipped) amount += 1.0
            if (state.built("Rice Panda")) amount += 0.25
            if ("Rice Shirt" in state.equipped) amount += 1.0
        }
        return amount
    }

    private val yieldEventPriority = listOf(
        "tornado", "tsunami", "greatFreeze", "doubleDelivery", "bountifulHarvest", "insectPlague",
    )

    private fun activeYieldEvent(state: YieldFarm): String? {
        val calendar = state.farm.optJSONObject("calendar") ?: return null
        return yieldEventPriority.firstOrNull { name ->
            val startedAt = calendar.optJSONObject(name)?.optLong("startedAt") ?: 0L
            if (startedAt > 0) state.checkAgainAt(startedAt, startedAt + DAY_MS)
            startedAt > 0 && startedAt > state.now - DAY_MS && startedAt <= state.now
        }
    }

    private fun harvestCalendarBonus(state: YieldFarm): Double? {
        if (activeYieldEvent(state) != "bountifulHarvest") return 0.0
        val season = state.farm.optJSONObject("season")?.optString("season")?.lowercase()
        val guardian = guardianBySeason[season] ?: return null
        return if (state.built(guardian)) 2.0 else 1.0
    }

    private fun Double.roundYield(): Double = round(this * 10_000.0) / 10_000.0

    private const val DAY_MS = 86_400_000L
}
