package com.sfl.browser.notify

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

internal object AnimalYields {
    private val chickenXp = intArrayOf(0, 60, 120, 240, 360, 480, 660, 840, 1020, 1200, 1440, 1680, 1920, 2160, 2400, 2720)
    private val sheepXp = intArrayOf(0, 120, 240, 480, 720, 960, 1320, 1680, 2040, 2400, 2880, 3360, 3840, 4320, 4800, 5440)
    private val cowXp = intArrayOf(0, 180, 360, 720, 1080, 1440, 1980, 2520, 3060, 3600, 4320, 5040, 5760, 6480, 7200, 8160)

    /** Null indicates insufficient data; an empty map means no products. */
    fun predict(animal: JSONObject, farm: YieldFarm): Map<String, Double>? {
        val state = animal.optString("state")
        if (state == "sick") return emptyMap()
        if (state !in setOf("ready", "idle", "happy", "sad") || farm.skills == null) return null
        val type = animal.optString("type")
        val thresholds = when (type) {
            "Chicken" -> chickenXp
            "Sheep" -> sheepXp
            "Cow", "Pig" -> cowXp
            else -> return null
        }
        val xp = animal.optDouble("experience", Double.NaN)
        if (!xp.isFinite() || xp < 0) return null
        val current = thresholds.indexOfLast { xp >= it }
        val level = if (state == "ready") current else (current + 1).coerceAtMost(15)
        val multiplier = animal.optDouble("multiplier", 0.0)
        if (!multiplier.isFinite() || multiplier < 0) return null

        val drops = baseDrops(type, level)
        return drops.mapValues { (item, base) ->
            var amount = decimal(base.toDouble())
            if (item == "Truffle") amount *= decimal(1 + farm.ranked("Truffle Yield", 0.1, 0.2, 0.3))
            if (multiplier != 0.0) amount *= decimal(multiplier)
            if (animal.optJSONObject("feedBuff")?.optString("name") == "Salt Lick") {
                amount *= decimal(1.05)
            }
            amount += decimal(flatBonus(item, farm)).setScale(4, RoundingMode.HALF_UP)
            amount.setScale(2, RoundingMode.HALF_UP).toDouble()
        }
    }

    private fun baseDrops(type: String, level: Int): Map<String, Int> {
        if (level == 0) return emptyMap()
        val standard = when (level) {
            in 1..3 -> 1
            in 4..7 -> 2
            in 8..14 -> 3
            else -> 4
        }
        return when (type) {
            "Chicken" -> {
                val eggs = when (level) {
                    in 1..3 -> 1
                    in 4..7 -> 2
                    in 8..12 -> 3
                    in 13..14 -> 4
                    else -> 5
                }
                val feathers = when (level) {
                    in 1..2 -> 0
                    in 3..8 -> 1
                    in 9..14 -> 2
                    else -> 3
                }
                if (feathers == 0) mapOf("Egg" to eggs)
                else mapOf("Egg" to eggs, "Feather" to feathers)
            }
            "Pig" -> mapOf("Truffle" to standard)
            else -> {
                val fibre = when (level) {
                    in 1..5 -> 1
                    in 6..9 -> 2
                    in 10..14 -> 3
                    else -> 4
                }
                if (type == "Cow") mapOf("Milk" to standard, "Leather" to fibre)
                else mapOf("Wool" to standard, "Merino Wool" to fibre)
            }
        }
    }

    private fun flatBonus(item: String, farm: YieldFarm): Double {
        var bonus = 0.0
        fun built(name: String, value: Double) {
            if (farm.built(name)) bonus += value
        }
        fun gear(name: String, value: Double) {
            if (name in farm.equipped) bonus += value
        }
        when (item) {
            "Egg" -> {
                built("Chicken Coop", 1.0); built("Rich Chicken", 0.1)
                built("Undead Rooster", 0.1); built("Ayam Cemani", 0.2)
                built("Squid Chicken", 0.1); built("Ascended Chicken", 0.1)
            }
            "Feather" -> { gear("Chicken Suit", 1.0); built("Alien Chicken", 0.1) }
            "Wool" -> {
                gear("Black Sheep Onesie", 2.0); gear("White Sheep Onesie", 0.25)
                built("Astronaut Sheep", 0.1); built("Ascended Sheep", 0.05)
            }
            "Merino Wool" -> { gear("Merino Jumper", 1.0); built("Toxic Tuft", 0.1) }
            "Milk" -> {
                built("Longhorn Cowfish", 0.2); built("Spa Cow", 0.1)
                gear("Milk Apron", 0.5); gear("Cowbell Necklace", 2.0)
            }
            "Leather" -> {
                built("Moo-ver", 0.25); built("Mootant", 0.1)
                gear("Training Whistle", 1.0)
            }
        }
        if (item in setOf("Egg", "Milk", "Wool")) {
            bonus += farm.ranked("Abundant Harvest", 0.2, 0.35, 0.5)
        }
        val favoured = when (item) {
            "Feather" -> "Featherweight"
            "Leather" -> "Leathercraft Mastery"
            "Merino Wool" -> "Merino Whisperer"
            else -> null
        }
        if (favoured != null) {
            bonus += farm.ranked("Fine Fibers", 0.1, 0.15, 0.2)
            for (skill in listOf("Featherweight", "Leathercraft Mastery", "Merino Whisperer")) {
                bonus += if (skill != favoured) {
                    -farm.ranked(skill, 0.1, 0.15, 0.2)
                } else when (skill) {
                    "Featherweight" -> farm.ranked(skill, 0.35, 0.45, 0.55)
                    "Leathercraft Mastery" -> farm.ranked(skill, 0.35, 0.6, 0.8)
                    else -> farm.ranked(skill, 0.35, 0.6, 0.9)
                }
            }
        }
        if (farm.built("Bale") && (item == "Egg" ||
                (item in setOf("Milk", "Wool") && farm.rank("Bale Economy") > 0))) {
            val bale = when (farm.rank("Double Bale")) {
                1 -> 0.2
                2 -> 0.25
                3 -> 0.3
                else -> 0.1
            }
            bonus += bale
        }
        gear("Cattlegrim", 0.25)
        if ((farm.farm.optJSONObject("inventory")?.optDouble("Barn Manager", 0.0) ?: 0.0) > 0) bonus += 0.1
        bonus += farm.bestBud { type, stem ->
            (if (type == "Retreat") 0.2 else 0.0) +
                (if (item == "Egg" && stem == "Egg Head") 0.2 else 0.0)
        }
        return bonus
    }

    private fun decimal(value: Double): BigDecimal = BigDecimal.valueOf(value)
}
