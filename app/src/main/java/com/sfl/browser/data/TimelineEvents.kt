package com.sfl.browser.data

import android.content.Context
import com.sfl.browser.R
import com.sfl.browser.notify.FarmTimer
import com.sfl.browser.notify.FarmTimers
import com.sfl.browser.notify.SflApi
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object TimelineEvents {
    private const val FRESH_MS = 60_000L
    private val kinds = listOf("auctions", "raffles")

    fun cached(context: Context, now: Long = System.currentTimeMillis()): List<FarmTimer> {
        val prefs = context.getSharedPreferences("timeline_events", Context.MODE_PRIVATE)
        return kinds.flatMap { kind ->
            val array = try {
                JSONArray(prefs.getString(kind, "[]") ?: "[]")
            } catch (e: JSONException) {
                android.util.Log.w("TimelineEvents", "Invalid $kind cache", e)
                JSONArray()
            }
            FarmTimers.fromJson(array).groupBy { it.key.substringBeforeLast(':') }
                .values.filter { group ->
                    val end = group.firstOrNull { it.key.endsWith(":end") }
                    end?.let { it.readyAt > now } ?: group.any { it.readyAt > now }
                }.flatten()
        }
    }

    /** Returns false if either endpoint could not be refreshed. */
    fun refresh(context: Context): Boolean {
        val prefs = context.getSharedPreferences("timeline_events", Context.MODE_PRIVATE)
        var complete = true
        for (kind in kinds) {
            if (System.currentTimeMillis() - prefs.getLong("$kind.at", 0) < FRESH_MS) continue
            val path = "/community/data?type=$kind"
            val array = if (kind == "auctions") {
                (SflApi.getData(context, path) as? SflApi.ApiResult.Success)
                    ?.body?.optJSONArray("auctions")
            } else {
                (SflApi.getDataArray(context, path) as? SflApi.ArrayResult.Success)?.array
            }
            if (array == null) {
                complete = false
                continue
            }
            store(context, kind, array)
        }
        return complete
    }

    fun store(context: Context, kind: String, data: JSONArray) {
        val timers = entries(context, kind, data)
        context.getSharedPreferences("timeline_events", Context.MODE_PRIVATE).edit()
            .putString(kind, FarmTimers.toJson(timers).toString())
            .putLong("$kind.at", System.currentTimeMillis()).apply()
    }

    private fun entries(context: Context, kind: String, data: JSONArray): List<FarmTimer> = buildList {
        val auction = kind == "auctions"
        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            val id = entry.optString(if (auction) "auctionId" else "id")
                .ifBlank { entry.optString("id") }.takeIf { it.isNotBlank() } ?: continue
            val prizes = if (auction) emptyList() else rafflePrizes(entry.optJSONObject("prizes"))
            val name = if (auction) {
                sequenceOf("wearable", "collectible", "nft", "type").mapNotNull { key ->
                    when (val value = entry.opt(key)) {
                        is String -> value.takeIf(String::isNotBlank)
                        is org.json.JSONObject -> value.optString("name").takeIf(String::isNotBlank)
                        else -> null
                    }
                }.firstOrNull() ?: context.getString(R.string.timeline_auction_name)
            } else prizes.asSequence().flatMap { it.rewards.asSequence().map(Reward::name) }
                .distinct().take(3).joinToString(", ").ifBlank { context.getString(R.string.timeline_raffle_name) }
            val start = entry.optLong("startAt")
            val end = entry.optLong("endAt")
            if (start > 0) add(FarmTimer(
                "event:$kind:$id:start",
                context.getString(if (auction) R.string.timeline_auction_starts else R.string.timeline_raffle_starts, name),
                start, ItemIcons.pathForName(context, name),
                prizes.joinToString("\n") { prize ->
                    val rewards = prize.rewards.joinToString(", ") { reward ->
                        reward.amount?.let { context.getString(R.string.timeline_prize_reward, it, reward.name) }
                            ?: reward.name
                    }
                    context.getString(R.string.timeline_prize_place, prize.place, rewards)
                },
            ))
            if (end > 0) add(FarmTimer(
                "event:$kind:$id:end",
                context.getString(if (auction) R.string.timeline_auction_ends else R.string.timeline_raffle_ends, name),
                end, ItemIcons.pathForName(context, name), "",
            ))
        }

    }

    internal data class Reward(val name: String, val amount: String? = null)
    internal data class Prize(val place: Int, val rewards: List<Reward>)

    internal fun rafflePrizes(prizes: JSONObject?): List<Prize> = prizes?.keys()?.asSequence()
        ?.mapNotNull { key ->
            val place = key.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val prize = prizes.optJSONObject(key) ?: return@mapNotNull null
            val rewards = buildList {
                for (field in listOf("items", "wearables")) {
                    val items = prize.optJSONObject(field) ?: continue
                    for (name in items.keys()) {
                        val amount = items.optString(name).toBigDecimalOrNull()
                        if (name.isNotBlank() && amount != null && amount.signum() > 0) {
                            val formatted = if (amount.scale() in -30..30 && amount.precision() <= 30) {
                                amount.stripTrailingZeros().toPlainString()
                            } else amount.toString()
                            add(Reward(name, formatted))
                        }
                    }
                }
                (prize.opt("nft") as? String)?.takeIf(String::isNotBlank)?.let { add(Reward(it)) }
            }
            rewards.takeIf { it.isNotEmpty() }?.let { Prize(place, it) }
        }?.sortedBy(Prize::place)?.toList().orEmpty()
}
