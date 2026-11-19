package com.sfl.browser.notify

import org.json.JSONObject
import java.math.BigDecimal

internal class MachineYields(farm: JSONObject, farmId: Long?) {
    private val rolls = HarvestRolls(farm, farmId)

    fun amount(pack: JSONObject, predictSeed: (String, YieldRoll?) -> Double?): Double? {
        val crop = pack.optString("crop")
        val stored = FarmYield.stored(pack)
        val count = pack.optDouble("seeds")
        if (crop.isBlank() || count !in 1.0..Int.MAX_VALUE.toDouble() || count != count.toInt().toDouble()) {
            return stored
        }
        val seeds = count.toInt()
        if (stored != null) {
            rolls.skip(crop, seeds)
            return stored
        }
        // Current packs are capped by seed stock; bound work on corrupt snapshots.
        if (seeds > 10_000) {
            rolls.skip(crop, seeds)
            return null
        }
        var amount = BigDecimal.ZERO
        repeat(seeds) { index ->
            val seedYield = predictSeed(crop, rolls.next(crop))
            if (seedYield == null || !seedYield.isFinite() || seedYield < 0) {
                rolls.skip(crop, seeds - index - 1)
                return null
            }
            amount += BigDecimal.valueOf(seedYield)
        }
        return amount.toDouble()
    }
}
