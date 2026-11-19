package com.sfl.browser.notify

import org.json.JSONObject
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.math.round

internal class YieldFarm(val farm: JSONObject, private val growth: CropGrowth, val now: Long) {
    val skills: JSONObject? = farm.optJSONObject("bumpkin")?.optJSONObject("skills")
    val equipped: Set<String> = buildSet {
        fun addGear(player: JSONObject?) {
            val gear = player?.optJSONObject("equipped") ?: return
            gear.keys().forEach { add(gear.optString(it)) }
        }
        addGear(farm.optJSONObject("bumpkin"))
        val helpers = farm.optJSONObject("farmHands")?.optJSONObject("bumpkins")
        helpers?.keys()?.forEach { addGear(helpers.optJSONObject(it)) }
    }

    fun rank(name: String): Int = (skills?.optInt(name) ?: 0).coerceIn(0, 3)
    fun ranked(name: String, first: Double, second: Double, third: Double): Double =
        when (rank(name)) {
            1 -> first
            2 -> second
            3 -> third
            else -> 0.0
        }

    fun built(name: String): Boolean = growth.isBuilt(name, now)

    fun checkAgainAt(vararg times: Long) = growth.checkAgainAt(*times)

    fun factionQuiver(): Boolean {
        val item = when (farm.optJSONObject("faction")?.optString("name")) {
            "bumpkins" -> "Bumpkin Quiver"
            "goblins" -> "Goblin Quiver"
            "nightshades" -> "Nightshade Quiver"
            "sunflorians" -> "Sunflorian Quiver"
            else -> return false
        }
        return item in equipped
    }

    fun bestBud(benefit: (type: String, stem: String) -> Double): Double {
        val buds = farm.optJSONObject("buds") ?: return 0.0
        return buds.keys().asSequence().mapNotNull { id ->
            val bud = buds.optJSONObject(id) ?: return@mapNotNull null
            if (bud.optJSONObject("coordinates") == null) return@mapNotNull null
            val aura = when (bud.optString("aura")) {
                "Basic" -> 1.05
                "Green" -> 1.2
                "Rare" -> 2.0
                "Mythical" -> 5.0
                else -> 1.0
            }
            round(aura * benefit(bud.optString("type"), bud.optString("stem")) * 10_000) / 10_000
        }.maxOrNull() ?: 0.0
    }

    fun temporary(name: String, days: Int, at: Long): Boolean =
        growth.hasTemporaryCollectible(name, days * 86_400_000L, at)

    fun crimstone(node: JSONObject, stone: JSONObject): Double? {
        val minesLeft = node.optInt("minesLeft", -1)
        val minedAt = stone.optLong("minedAt")
        if (minesLeft <= 0 || minedAt <= 0 || skills == null) return null
        val at = maxOf(now, minedAt + stone.optLong("baseDurationMs"))
        var amount = 100
        if (built("Crimson Carp")) amount += 5
        if (built("Crim Peckster")) amount += 10
        if ("Crimstone Armor" in equipped) amount += 10
        val streakEndsAt = minedAt + 172_800_000L
        if (minesLeft == 1 && at <= streakEndsAt) {
            checkAgainAt(streakEndsAt + 1)
            amount += 200 + (ranked("Fire Kissed", 1.0, 1.35, 1.75) * 100).roundToInt()
            if ("Crimstone Hammer" in equipped) amount += 200
            if (built("Mushroom Chicken")) amount += 50
        }
        return amount / 100.0
    }

    fun fullHive(): Double? {
        if (skills == null) return null
        var amount = 100
        if ("Bee Suit" in equipped) amount += 10
        if ("Honeycomb Shield" in equipped) amount += 100
        amount += intArrayOf(0, 10, 15, 20)[rank("Sweet Bonus")]
        if (built("King of Bears")) amount += 25
        if (built("Ruins Flower")) amount += 5
        return amount / 100.0
    }

    fun saltPerHarvest(salt: JSONObject, readyAt: Long): Double? {
        if (skills == null || readyAt <= 0) return null
        val at = maxOf(now, readyAt)
        var amount = 10.0 + ranked("Wide Rakes", 2.0, 3.0, 4.0)
        if ("Deep Sea Salt Cave Background" in equipped) amount += 5
        if (built("Salt Worker Gnome")) amount += 2
        if (at < SALT_CHAPTER_START) checkAgainAt(SALT_CHAPTER_START)
        if (at >= SALT_CHAPTER_START && at < SALT_CHAPTER_END) {
            val vip = farm.optJSONObject("vip")
            val trial = vip?.optLong("trialStartedAt") ?: 0L
            val expiresAt = vip?.optLong("expiresAt") ?: 0L
            val lifetime = farm.optJSONObject("inventory")?.optDouble("Lifetime Farmer Banner", 0.0) ?: 0.0
            checkAgainAt(SALT_CHAPTER_END)
            if (lifetime <= 0) checkAgainAt(expiresAt, trial, if (trial > 0) trial + 604_800_000L else 0L)
            if (lifetime > 0 || expiresAt > at ||
                (trial > 0 && trial <= at && at - trial < 604_800_000L)) amount += 2
        }
        val legacySlots = salt.optJSONObject("harvesting")?.optJSONArray("slots")?.length() ?: 0
        return amount * (1 + legacySlots)
    }

    private companion object {
        val SALT_CHAPTER_START = Instant.parse("2026-05-04T00:00:00Z").toEpochMilli()
        val SALT_CHAPTER_END = Instant.parse("2026-08-03T00:00:00Z").toEpochMilli()
    }
}
