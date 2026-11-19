package com.sfl.browser.notify

import org.json.JSONObject
import kotlin.math.round

/**
 * Predicts one crop harvest. Reuse an instance for a batch, and call crops in
 * ready-time order so per-cell area-of-effect cooldowns match harvest order.
 * Passing no plot is supported for Crop Machine crops; plot-only bonuses are
 * then omitted.
 */
internal class FieldCropYieldCalculator(private val state: YieldFarm) {
    private data class Point(val x: Int, val y: Int)

    private val farm = state.farm
    private val area = farm.optJSONObject("aoe")
    private val lastUse = mutableMapOf<Triple<String, Int, Int>, Long>()

    fun predict(
        crop: JSONObject,
        plot: JSONObject? = null,
        harvestAt: Long = state.now,
        roll: YieldRoll? = null,
    ): Double? {
        val name = crop.optString("name")
        val tier = CropYieldRules.tier(name) ?: return FarmYield.stored(crop)
        FarmYield.stored(crop)?.let { return it }
        if (state.skills == null) return null

        fun hit(effect: String, chance: Double): Boolean? =
            roll?.hit(effect, chance)

        val at = maxOf(state.now, harvestAt)
        var amount = 1.0
        if ("Green Amulet" in state.equipped) {
            if (hit("Green Amulet", 10.0) ?: return null) amount *= 10.0
        }
        when (name) {
            "Cauliflower" -> if (state.built("Golden Cauliflower")) amount *= 2.0
            "Carrot" -> if (state.built("Easter Bunny")) amount *= 1.2
            "Pumpkin" -> if (state.built("Victoria Sisters")) amount *= 1.2
            "Parsnip" -> if ("Parsnip" in state.equipped) amount *= 1.2
            "Beetroot" -> if ("Beetroot Amulet" in state.equipped) amount *= 1.2
            "Sunflower" -> if ("Sunflower Amulet" in state.equipped) amount *= 1.1
        }
        if (state.built("Scarecrow") || state.built("Kuebiko")) amount *= 1.2
        if ((farm.optJSONObject("inventory")?.optDouble("Coder") ?: 0.0) >= 1.0) amount *= 1.2

        if (activeBuff("Power hour", at)) amount += 0.2
        when (name) {
            "Potato" -> {
                if (state.built("Peeled Potato") && hit("Peeled Potato", 20.0) ?: return null) amount += 1.0
                if (state.built("Potent Potato") && hit("Potent Potato", 10.0 / 3.0) ?: return null) amount += 10.0
            }
            "Sunflower" -> if (state.built("Stellar Sunflower") &&
                hit("Stellar Sunflower", 10.0 / 3.0) ?: return null
            ) amount += 10.0
            "Radish" -> {
                if (state.built("Radical Radish") &&
                    hit("Radical Radish", 10.0 / 3.0) ?: return null
                ) amount += 10.0
                if (state.built("Lab Grown Radish")) amount += 0.4
            }
            "Cabbage" -> when {
                state.built("Cabbage Boy") -> {
                    amount += 0.25
                    if (state.built("Cabbage Girl")) amount += 0.25
                }
                state.built("Karkinos") -> amount += 0.1
            }
            "Carrot" -> {
                if (state.built("Pablo The Bunny")) amount += 0.1
                if (state.built("Lab Grown Carrot")) amount += 0.2
            }
            "Kale" -> {
                if (state.built("Foliant")) amount += 0.2
                if (state.built("Giant Kale")) amount += 2.0
            }
            "Eggplant" -> {
                if (state.built("Purple Trail")) amount += 0.2
                if (state.built("Maximus")) amount += 1.0
                if ("Eggplant Onesie" in state.equipped) amount += 0.1
            }
            "Artichoke" -> if (state.built("Giant Artichoke")) amount += 2.0
            "Yam" -> if (state.built("Giant Yam")) amount += 0.5
            "Soybean" -> {
                if ("Tofu Mask" in state.equipped) amount += 0.1
                if (state.built("Soybliss")) amount += 1.0
            }
            "Corn" -> {
                if ("Corn Onesie" in state.equipped) amount += 0.1
                if ("Corn Silk Hair" in state.equipped) amount += 2.0
                if (state.built("Poppy")) amount += 0.1
            }
            "Wheat" -> if ("Sickle" in state.equipped) amount += 2.0
            "Barley" -> if (state.built("Sheaf of Plenty")) amount += 2.0
            "Pumpkin" -> {
                if (state.built("Freya Fox")) amount += 0.5
                if (state.built("Lab Grown Pumpkin")) amount += 0.3
            }
            "Onion" -> if (state.built("Giant Onion")) amount += 3.0
        }
        val fertiliser = plot?.optJSONObject("fertiliser")?.optString("name")
        if (fertiliser == "Sprout Mix" || fertiliser == "Sproutroot Surprise") {
            amount += 0.2
            if (state.built("Knowledge Crab")) amount += 0.2
        }
        val season = farm.optJSONObject("season")?.optString("season").orEmpty()
        if (CropYieldRules.isSeasonal(name, season) &&
            (season.equals("spring", true) && "Blossom Ward" in state.equipped ||
                season.equals("winter", true) && "Frozen Heart" in state.equipped)
        ) amount += 1.0

        if ("Infernal Pitchfork" in state.equipped) amount += 3.0
        if (state.temporary("Legendary Shrine", 1, at)) amount += 1.0
        if (state.factionQuiver()) amount += 0.25
        amount += state.bestBud { type, stem ->
            (if (type == "Plaza" && tier == CropTier.BASIC) 0.3 else 0.0) +
                (if (type == "Castle" && tier == CropTier.MEDIUM) 0.3 else 0.0) +
                (if (type == "Snow" && tier == CropTier.ADVANCED) 0.3 else 0.0) +
                (if (stem == "3 Leaf Clover") 0.5 else 0.0) +
                (if (stem == "Basic Leaf" && tier == CropTier.BASIC) 0.2 else 0.0) +
                (if (name == "Carrot" && stem == "Carrot Head") 0.3 else 0.0) +
                (if (name == "Sunflower" && stem == "Sunflower Hat") 0.5 else 0.0)
        }
        if (name in CropYieldRules.overnight && state.built("Hoot")) amount += 0.5

        if (plot != null) {
            val swarms = plot.optJSONObject("beeSwarm")?.optInt("count") ?: 0
            if (swarms > 0) {
                amount += swarms * (0.2 + state.ranked("Pollen Power Up", 0.1, 0.15, 0.2))
            }

            val plotX = plot.optInt("x", Int.MIN_VALUE)
            val plotY = plot.optInt("y", Int.MIN_VALUE)
            if (plotX == Int.MIN_VALUE || plotY == Int.MIN_VALUE) {
                if (hasRelevantAoe(name, tier)) return null
            } else {
                val plantedAt = crop.optLong("plantedAt")
                state.checkAgainAt(plantedAt + 1)
                val cooldown = at - plantedAt
                if (cooldown <= 0 && hasRelevantAoe(name, tier)) return null
                areaBonus(name, tier, plotX, plotY, cooldown, at)?.let { amount += it }
            }
        }

        amount += when (tier) {
            CropTier.BASIC -> state.ranked("Young Farmer", 0.1, 0.125, 0.15)
            CropTier.MEDIUM -> state.ranked("Experienced Farmer", 0.1, 0.125, 0.15)
            CropTier.ADVANCED -> state.ranked("Old Farmer", 0.1, 0.125, 0.15)
        }
        val farmRank = state.rank("Acre Farm")
        val farmBuff = state.ranked("Acre Farm", 1.0, 1.4, 1.8)
        val farmDebuff = state.ranked("Acre Farm", 0.5, 0.6, 0.7)
        if (farmRank > 0) amount += if (tier == CropTier.ADVANCED) farmBuff else -farmDebuff
        val hectareRank = state.rank("Hectare Farm")
        val hectareBuff = state.ranked("Hectare Farm", 1.0, 1.4, 1.8)
        val hectareDebuff = state.ranked("Hectare Farm", 0.5, 0.6, 0.7)
        if (hectareRank > 0) amount += if (tier == CropTier.ADVANCED) -hectareDebuff else hectareBuff

        if (activeCalendarEvent() == "insectPlague" &&
            farm.optJSONObject("calendar")?.optJSONObject("insectPlague")?.optBoolean("protected") != true
        ) amount *= 0.5
        if (activeCalendarEvent() == "bountifulHarvest") {
            amount += 1.0
            val guardian = when (season.lowercase()) {
                "spring" -> "Spring Guardian"
                "summer" -> "Summer Guardian"
                "autumn" -> "Autumn Guardian"
                "winter" -> "Winter Guardian"
                else -> null
            }
            if (guardian != null && state.built(guardian)) amount += 1.0
        }

        return round(amount * 10_000.0) / 10_000.0
    }

    private fun activeBuff(name: String, at: Long): Boolean {
        val buff = farm.optJSONObject("buffs")?.optJSONObject(name) ?: return false
        val from = buff.optLong("startedAt")
        val duration = buff.optLong("durationMS")
        if (from > 0 && duration > 0 && at < from + duration) state.checkAgainAt(from, from + duration)
        return from > 0 && duration > 0 && at >= from && at < from + duration
    }

    private fun activeCalendarEvent(): String? {
        val calendar = farm.optJSONObject("calendar") ?: return null
        return listOf(
            "tornado", "tsunami", "greatFreeze", "doubleDelivery",
            "bountifulHarvest", "insectPlague", "sunshower", "fullMoon", "fishFrenzy",
        ).firstOrNull { name ->
            val startedAt = calendar.optJSONObject(name)?.optLong("startedAt") ?: 0L
            if (startedAt > 0) state.checkAgainAt(startedAt, startedAt + DAY)
            startedAt > 0 && startedAt > state.now - DAY && startedAt <= state.now
        }
    }

    private fun hasRelevantAoe(name: String, tier: CropTier): Boolean =
        placement(GOLDENSNOUT) != null ||
            (tier == CropTier.ADVANCED && placement(LAURIE) != null) ||
            (tier == CropTier.MEDIUM && placement("Scary Mike") != null) ||
            (tier == CropTier.BASIC && state.rank("Chonky Scarecrow") > 1 &&
                placement("Basic Scarecrow") != null) ||
            (name == "Corn" && placement("Queen Cornelia") != null) ||
            (tier != CropTier.BASIC && placement("Gnome") != null &&
                placement("Cobalt") != null && placement("Clementine") != null)

    private fun areaBonus(
        crop: String,
        tier: CropTier,
        x: Int,
        y: Int,
        cooldown: Long,
        at: Long,
    ): Double {
        var result = 0.0
        val rank = state.rank("Laurie's Gains")
        if (tier == CropTier.ADVANCED) {
            placement(LAURIE)?.let { crow ->
                val dx = x - crow.x
                val dy = y - crow.y
                val left = intArrayOf(1, 3, 4, 4)[rank]
                val right = intArrayOf(1, 3, 3, 4)[rank]
                val depth = intArrayOf(3, 7, 8, 9)[rank]
                if (dx in -left..right && dy in -depth..-1 &&
                    canUseArea(LAURIE, dx, dy, cooldown, at)
                ) result += 0.2 + state.ranked("Laurie's Gains", 0.1, 0.15, 0.2)
            }
        }
        if (tier == CropTier.MEDIUM) {
            placement("Scary Mike")?.let { mike ->
                val dx = x - mike.x
                val dy = y - mike.y
                val horrorRank = state.rank("Horror Mike")
                val left = intArrayOf(1, 3, 4, 4)[horrorRank]
                val right = intArrayOf(1, 3, 3, 4)[horrorRank]
                val depth = intArrayOf(3, 7, 8, 9)[horrorRank]
                if (dx in -left..right && dy in -depth..-1 &&
                    canUseArea("Scary Mike", dx, dy, cooldown, at)
                ) result += 0.2 + state.ranked("Horror Mike", 0.1, 0.15, 0.2)
            }
        }
        if (tier == CropTier.BASIC) {
            placement("Basic Scarecrow")?.let { scarecrow ->
                val dx = x - scarecrow.x
                val dy = y - scarecrow.y
                val chonkyRank = state.rank("Chonky Scarecrow")
                val left = intArrayOf(1, 3, 4, 4)[chonkyRank]
                val right = intArrayOf(1, 3, 3, 4)[chonkyRank]
                val depth = intArrayOf(3, 7, 8, 9)[chonkyRank]
                val bonus = state.ranked("Chonky Scarecrow", 0.0, 0.05, 0.1)
                if (bonus > 0.0 && dx in -left..right && dy in -depth..-1 &&
                    canUseArea("Chonky Scarecrow", dx, dy, cooldown, at)
                ) result += bonus
            }
        }
        placement(GOLDENSNOUT)?.let { snout ->
            val dx = x - snout.x
            val dy = y - snout.y
            if (dx in -1..2 && dy in -2..1 && canUseArea(GOLDENSNOUT, dx, dy, cooldown, at)) {
                result += 0.5
            }
        }
        if (crop == "Corn") {
            placement("Queen Cornelia")?.let { queen ->
                val dx = x - queen.x
                val dy = y - queen.y
                if (dx in -1..1 && dy in -2..1 && canUseArea("Queen Cornelia", dx, dy, cooldown, at)) {
                    result += 1.0
                }
            }
        }
        if (tier != CropTier.BASIC) {
            val gnome = placement("Gnome")
            val cobalt = placement("Cobalt")
            val clementine = placement("Clementine")
            if (gnome != null && cobalt == Point(gnome.x - 1, gnome.y) &&
                clementine == Point(gnome.x + 1, gnome.y) &&
                x == gnome.x && y == gnome.y - 1 &&
                canUseArea("Gnome", 0, 1, cooldown, at)
            ) result += 10.0
        }
        return result
    }

    private fun canUseArea(name: String, dx: Int, dy: Int, cooldown: Long, at: Long): Boolean {
        val key = Triple(name, dx, dy)
        val usedAt = lastUse[key]
            ?: area?.optJSONObject(name)?.optJSONObject(dx.toString())?.optLong(dy.toString())
            ?: 0L
        if (cooldown <= 0 || at - usedAt < cooldown) return false
        lastUse[key] = at
        return true
    }

    private fun placement(name: String): Point? {
        val locations = listOfNotNull(
            farm.optJSONObject("collectibles"),
            farm.optJSONObject("home")?.optJSONObject("collectibles"),
            farm.optJSONObject("interior")?.optJSONObject("ground")?.optJSONObject("collectibles"),
            farm.optJSONObject("interior")?.optJSONObject("level_one")?.optJSONObject("collectibles"),
        )
        return locations.asSequence().mapNotNull { it.optJSONArray(name) }
            .flatMap { array -> (0 until array.length()).asSequence().mapNotNull { array.optJSONObject(it) } }
            .firstNotNullOfOrNull { entry ->
                val coordinates = entry.optJSONObject("coordinates") ?: return@firstNotNullOfOrNull null
                if (!coordinates.has("x") || !coordinates.has("y")) return@firstNotNullOfOrNull null
                val readyAt = entry.optLong("readyAt")
                if (readyAt > state.now) {
                    state.checkAgainAt(readyAt)
                    return@firstNotNullOfOrNull null
                }
                Point(coordinates.optInt("x"), coordinates.optInt("y"))
            }
    }

    private companion object {
        const val DAY = 86_400_000L
        const val LAURIE = "Laurie the Chuckle Crow"
        const val GOLDENSNOUT = "Sir Goldensnout"
    }
}
