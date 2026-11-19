package com.sfl.browser.notify

import org.json.JSONObject
import java.time.LocalDate

/** Reuse quotes for all thresholds on an item, or one market snapshot for the entire check. */
internal class PriceQuoteBatch(
    private val batched: Boolean,
    private val utcToday: () -> LocalDate,
    private val fetch: (path: String) -> SflApi.ApiResult,
) {
    data class Quote(val response: SflApi.ApiResult, val floor: Double? = null, val valid: Boolean = true)

    private val quotes = mutableMapOf<Pair<String, Int>, Quote>()
    private val snapshot by lazy {
        val response = fetch("/community/data?type=marketplaceActivity")
        val items = (response as? SflApi.ApiResult.Success)?.body
            ?.optJSONObject("reports")?.optJSONObject(utcToday().toString())?.optJSONObject("items")
        response to items
    }

    fun get(collection: String, id: Int): Quote = quotes.getOrPut(collection to id) {
        if (batched) {
            val (response, items) = snapshot
            val key = "$collection-$id"
            val item = items?.optJSONObject(key)
            when {
                items == null -> Quote(response, valid = false)
                item != null -> floorOf(response, item)
                items.has(key) -> Quote(response, valid = false)
                else -> Quote(response) // No entry means no trades or open listings.
            }
        } else {
            val response = fetch("/community/data?type=tradeable&collection=$collection&id=$id")
            if (response is SflApi.ApiResult.Success) floorOf(response, response.body) else Quote(response)
        }
    }

    private fun floorOf(response: SflApi.ApiResult, item: JSONObject): Quote {
        if (item.optInt("listingCount", -1) == 0) return Quote(response)
        val floor = item.optDouble("floor", Double.NaN)
        return if (floor.isFinite() && floor >= 0) Quote(response, floor) else Quote(response, valid = false)
    }
}
