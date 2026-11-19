package com.sfl.browser.notify

import org.json.JSONObject
import java.math.BigDecimal

internal data class FarmYield(val item: String, val amount: Double) {
    companion object {
        private const val DAY_MS = 86_400_000L
        private val FLOWER_CRIT_COLLECTIBLES = listOf(
            "Humming Bird", "Butterfly", "Desert Rose", "Chicory", "Salt Crystal Flower",
        )

        fun stored(entry: JSONObject): Double? =
            entry.optDouble("amount").takeIf { it.isFinite() && it >= 0 }

        fun readProducts(data: JSONObject): Map<String, Double>? {
            val products = linkedMapOf<String, Double>()
            for (item in data.keys()) {
                if (item.isBlank()) return null
                products[item] = data.optDouble(item).takeIf { it.isFinite() && it >= 0 } ?: return null
            }
            return products
        }

        fun forTimer(timer: FarmTimer): List<FarmYield>? {
            val amounts = timer.yieldProducts ?: timer.yieldAmount?.let {
                mapOf((timer.yieldItem ?: timer.label) to it)
            } ?: return null
            return amounts.map { (item, amount) ->
                if (item.isBlank() || !amount.isFinite() || amount < 0) return null
                FarmYield(item, amount)
            }
        }

        fun total(timers: List<FarmTimer>): FarmYield? = totals(timers)?.singleOrNull()

        fun totals(timers: List<FarmTimer>, allowMixedItems: Boolean = false): List<FarmYield>? {
            if (timers.isEmpty()) return null
            val sums = linkedMapOf<String, BigDecimal>()
            val multi = timers.first().yieldProducts != null
            for (timer in timers) {
                if (!allowMixedItems && (timer.yieldProducts != null) != multi) return null
                for ((item, amount) in forTimer(timer) ?: return null) {
                    if (!allowMixedItems && !multi && sums.isNotEmpty() && item !in sums) return null
                    sums[item] = (sums[item] ?: BigDecimal.ZERO) + BigDecimal.valueOf(amount)
                }
            }
            return sums.map { (item, sum) ->
                val amount = sum.toDouble().takeIf { it.isFinite() } ?: return null
                FarmYield(item, amount)
            }
        }

        fun obsidian(growth: CropGrowth, at: Long): Double {
            var hundredths = 100
            if (growth.isBuilt("Obsidian Turtle", at)) hundredths += 50
            if (growth.isBuilt("Magma Stone", at)) hundredths += 15
            if (growth.isBuilt("Macropinna", at)) hundredths += 10
            return hundredths / 100.0
        }

        fun flower(flower: JSONObject, growth: CropGrowth, skills: JSONObject?, at: Long): Double? {
            stored(flower)?.let { return it }
            if (skills == null) return null
            val hits = flower.optJSONObject("criticalHit")
            fun hit(name: String): Boolean = (hits?.optInt(name) ?: 0) > 0
            var amount = 1 + FLOWER_CRIT_COLLECTIBLES.count { hit(it) && growth.isBuilt(it, at) }
            if (hit("Moth Shrine") && growth.hasTemporaryCollectible("Moth Shrine", 7 * DAY_MS, at)) amount++
            if (hit("Petalled Perk") && skills.optInt("Petalled Perk") > 0) amount++
            if (growth.hasTemporaryCollectible("Legendary Shrine", DAY_MS, at)) amount++
            return amount.toDouble()
        }
    }
}
