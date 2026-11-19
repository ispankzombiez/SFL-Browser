package com.sfl.browser.notify

import org.json.JSONObject

/**
 * Kept as a source-compatible entry point for existing timer callers, which
 * historically route every plot through this class. New callers should use
 * [FieldCropYieldCalculator] directly.
 */
internal class BarleyYield(private val state: YieldFarm) {
    private val calculator = FieldCropYieldCalculator(state)

    fun amount(plot: JSONObject, readyAt: Long, greenAmuletHit: Boolean?): Double? {
        val crop = plot.optJSONObject("crop") ?: return null
        FarmYield.stored(crop)?.let { return it }
        if ("Green Amulet" in state.equipped && greenAmuletHit == null) return null
        val name = crop.optString("name")
        val hasUnprovidedCritical = when (name) {
            "Potato" -> state.built("Peeled Potato") || state.built("Potent Potato")
            "Sunflower" -> state.built("Stellar Sunflower")
            "Radish" -> state.built("Radical Radish")
            else -> false
        }
        if (hasUnprovidedCritical) return null
        val roll = if ("Green Amulet" in state.equipped) {
            YieldRoll { _, _ -> greenAmuletHit == true }
        } else null
        return calculator.predict(crop, plot, readyAt, roll)
    }
}
