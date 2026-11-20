package com.sfl.browser.notify

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

internal enum class MarketplaceTradeKind(val source: String) {
    LISTING_SOLD("listing"),
    OFFER_ACCEPTED("offer"),
}

internal data class MarketplaceTradeAlert(
    val kind: MarketplaceTradeKind,
    val id: String,
    val trade: JSONObject,
)

internal object MarketplaceTradeAlerts {
    data class Scan(val seen: JSONObject, val alerts: List<MarketplaceTradeAlert>)

    data class ListingTotals(val quantity: BigInteger?, val flower: BigDecimal?)

    fun notificationGroups(alerts: List<MarketplaceTradeAlert>): List<List<MarketplaceTradeAlert>> {
        val (listings, offers) = alerts.partition { it.kind == MarketplaceTradeKind.LISTING_SOLD }
        return buildList {
            if (listings.isNotEmpty()) add(listings)
            offers.forEach { add(listOf(it)) }
        }
    }

    fun listingTotals(listings: List<MarketplaceTradeAlert>): ListingTotals {
        val quantities = listings.mapNotNull {
            it.trade.optString("quantity").toBigIntegerOrNull()?.takeIf { quantity -> quantity.signum() > 0 }
        }
        val amounts = listings.mapNotNull {
            it.trade.optString("sfl").toBigDecimalOrNull()?.takeIf { amount -> amount.signum() >= 0 }
        }
        return ListingTotals(
            quantity = if (quantities.size == listings.size) quantities.sumOf { it } else null,
            flower = if (amounts.size == listings.size) amounts.sumOf { it }.stripTrailingZeros() else null,
        )
    }

    fun scan(trades: JSONArray, farmId: String, previous: JSONObject?, baseline: Boolean): Scan {
        val seen = JSONObject()
        val alerts = mutableListOf<MarketplaceTradeAlert>()
        for (i in 0 until trades.length()) {
            val trade = trades.optJSONObject(i) ?: continue
            val id = (trade.opt("id") as? String)?.takeIf(String::isNotBlank) ?: continue
            if (seen.has(id)) continue
            val fulfilledAt = trade.optLong("fulfilledAt")
            if (fulfilledAt <= 0L) continue
            if (previous?.has(id) == true) {
                seen.put(id, fulfilledAt)
                continue
            }
            val kind = MarketplaceTradeKind.values().firstOrNull { it.source == trade.opt("source") } ?: continue
            val initiator = trade.optJSONObject("initiatedBy")?.opt("id")?.toString()
            val fulfiller = trade.optJSONObject("fulfilledBy")?.opt("id")
            if (initiator.isNullOrBlank() || initiator == "null" || fulfiller == null || fulfiller == JSONObject.NULL) continue
            seen.put(id, fulfilledAt)
            if (!baseline && previous != null && initiator == farmId) {
                alerts += MarketplaceTradeAlert(kind, id, trade)
            }
        }
        return Scan(seen, alerts)
    }
}
