package com.sfl.browser.notify

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

class MarketplaceTradeAlertsTest {
    @Test
    fun onlyOurListingsAndOurBuyOffersProduceAlerts() {
        val trades = JSONArray()
            .put(trade("our-listing", "listing", 7, 8))
            .put(trade("our-offer", "offer", 7, 8))
            .put(trade("we-bought-a-listing", "listing", 8, 7))
            .put(trade("we-accepted-their-offer", "offer", 8, 7))
        val result = MarketplaceTradeAlerts.scan(trades, "7", JSONObject(), baseline = false)
        assertEquals(
            listOf("our-listing" to MarketplaceTradeKind.LISTING_SOLD, "our-offer" to MarketplaceTradeKind.OFFER_ACCEPTED),
            result.alerts.map { it.id to it.kind },
        )
    }

    @Test
    fun firstSuccessfulProfileIsSilentAndNewOffersAreNotReplayed() {
        val existing = trade("old", "offer", 7, 8)
        val accepted = trade("new", "offer", 7, 8)
        val initial = MarketplaceTradeAlerts.scan(JSONArray().put(existing), "7", null, baseline = false)
        assertTrue(initial.alerts.isEmpty())
        val nextTrades = JSONArray().put(existing).put(accepted).put(accepted)
        val next = MarketplaceTradeAlerts.scan(nextTrades, "7", initial.seen, baseline = false)
        assertEquals(listOf("new"), next.alerts.map { it.id })
        assertTrue(MarketplaceTradeAlerts.scan(nextTrades, "7", next.seen, baseline = false).alerts.isEmpty())
    }

    @Test
    fun incompleteOrUnclassifiedRowsDoNotRaiseOrConsumeAnOfferAlert() {
        val pending = trade("pending", "offer", 7, 8).put("fulfilledAt", 0)
        val ambiguous = trade("ambiguous", "other", 7, 8)
        val scan = MarketplaceTradeAlerts.scan(JSONArray().put(pending).put(ambiguous),
            "7", JSONObject(), baseline = false)
        assertTrue(scan.alerts.isEmpty())
        val fulfilled = MarketplaceTradeAlerts.scan(JSONArray().put(trade("pending", "offer", 7, 8)),
            "7", scan.seen, baseline = false)
        assertEquals("pending", fulfilled.alerts.single().id)
    }

    @Test
    fun twentyNewSalesMakeOneBatchWithExactTotalsAndOffersStaySeparate() {
        val oldListing = trade("old", "listing", 7, 8).put("quantity", 100).put("sfl", "100")
        val rows = JSONArray().put(oldListing)
        repeat(20) { index ->
            rows.put(trade("listing-$index", "listing", 7, 8).put("quantity", 25).put("sfl", "0.11"))
        }
        rows.put(trade("offer-1", "offer", 7, 8).put("quantity", 90).put("sfl", "90.5"))
        rows.put(trade("offer-2", "offer", 7, 8).put("quantity", 50).put("sfl", "65"))
        val scan = MarketplaceTradeAlerts.scan(rows, "7", JSONObject().put("old", 1000), baseline = false)
        val groups = MarketplaceTradeAlerts.notificationGroups(scan.alerts)
        assertEquals(listOf(20, 1, 1), groups.map { it.size })
        assertEquals(listOf("offer-1", "offer-2"), groups.drop(1).map { it.single().id })
        assertEquals(
            MarketplaceTradeAlerts.ListingTotals(BigInteger("500"), BigDecimal("2.2")),
            MarketplaceTradeAlerts.listingTotals(groups.first()),
        )
        val repeated = MarketplaceTradeAlerts.scan(rows, "7", scan.seen, baseline = false)
        assertTrue(MarketplaceTradeAlerts.notificationGroups(repeated.alerts).isEmpty())
    }

    @Test
    fun batchDoesNotShowAPartialFlowerSumAsTheTotal() {
        val rows = JSONArray()
            .put(trade("a", "listing", 7, 8).put("quantity", 2).put("sfl", 3.5))
            .put(trade("b", "listing", 7, 8).put("quantity", 4))
        val scan = MarketplaceTradeAlerts.scan(rows, "7", JSONObject(), baseline = false)
        val totals = MarketplaceTradeAlerts.listingTotals(MarketplaceTradeAlerts.notificationGroups(scan.alerts).single())
        assertEquals(BigInteger("6"), totals.quantity)
        assertNull(totals.flower)
    }

    private fun trade(id: String, source: String, from: Int, by: Int) = JSONObject()
        .put("id", id)
        .put("source", source)
        .put("fulfilledAt", 1000)
        .put("initiatedBy", JSONObject().put("id", from))
        .put("fulfilledBy", JSONObject().put("id", by))
}
