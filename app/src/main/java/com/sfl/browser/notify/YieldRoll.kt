package com.sfl.browser.notify

import org.json.JSONObject

internal class HarvestRolls(farm: JSONObject, private val farmId: Long?) {
    private val activity = farm.optJSONObject("farmActivity")
    private val counters = mutableMapOf<String, Int>()

    fun next(item: String): YieldRoll? {
        val id = farmId?.takeIf { it > 0 } ?: return null
        val history = activity ?: return null
        val counter = counters[item] ?: history.optInt("$item Harvested")
        val roll = YieldRoll.forHarvest(id, item, counter) ?: return null
        counters[item] = counter + 1
        return roll
    }

    fun skip(item: String, count: Int) {
        val history = activity ?: return
        counters[item] = (counters[item] ?: history.optInt("$item Harvested")) + count
    }
}

/** A deterministic critical-drop decision for one item and harvest counter. */
internal fun interface YieldRoll {
    fun hit(criticalHitName: String, chancePercent: Double): Boolean

    companion object {
        private val itemIds = mapOf(
            "Sunflower" to 201,
            "Potato" to 202,
            "Pumpkin" to 203,
            "Carrot" to 204,
            "Cabbage" to 205,
            "Beetroot" to 206,
            "Cauliflower" to 207,
            "Parsnip" to 208,
            "Radish" to 209,
            "Wheat" to 210,
            "Kale" to 211,
            "Apple" to 212,
            "Blueberry" to 213,
            "Orange" to 214,
            "Eggplant" to 215,
            "Corn" to 216,
            "Banana" to 217,
            "Soybean" to 251,
            "Grape" to 252,
            "Rice" to 253,
            "Olive" to 254,
            "Tomato" to 255,
            "Lemon" to 256,
            "Barley" to 257,
            "Rhubarb" to 258,
            "Zucchini" to 259,
            "Yam" to 260,
            "Broccoli" to 261,
            "Pepper" to 262,
            "Onion" to 263,
            "Turnip" to 264,
            "Artichoke" to 265,
            "Duskberry" to 266,
            "Lunara" to 267,
            "Celestine" to 268,
            "Saltwort" to 3027,
        )

        fun forHarvest(farmId: Long, item: String, counter: Int): YieldRoll? {
            val itemId = itemIds[item] ?: return null
            return forItem(farmId, itemId, counter)
        }

        fun forItem(farmId: Long, itemId: Int, counter: Int): YieldRoll =
            YieldRoll { criticalHitName, chancePercent ->
                val value = randomValue(farmId, itemId, counter, criticalHitName)
                value * 100.0 < chancePercent
            }

        fun randomValue(
            farmId: Long,
            itemId: Int,
            counter: Int,
            criticalHitName: String,
        ): Double {
            var nameHash = 0
            for (character in criticalHitName) {
                nameHash = nameHash * 31 + character.code
            }
            val seed = farmId.toInt() * 0x85ebca6b.toInt() +
                itemId * 0x9e3779b9.toInt() +
                counter * 0x27d4eb2f +
                nameHash * 0x517cc1b7

            var mixed = seed xor (seed ushr 16)
            mixed *= 0x21f0aaad
            mixed = mixed xor (mixed ushr 15)
            mixed *= 0x735a2d97
            mixed = mixed xor (mixed ushr 15)
            return (mixed.toLong() and 0xffff_ffffL).toDouble() / 4_294_967_296.0
        }
    }
}
