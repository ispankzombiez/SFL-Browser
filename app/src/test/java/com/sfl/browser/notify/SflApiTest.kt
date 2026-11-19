package com.sfl.browser.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CancellationException

class SflApiTest {
    private val today = LocalDate.parse("2026-11-18")
    private val quote = SflApi.ApiResult.Success(JSONObject().put("floor", 12))

    @Test
    fun livePricesReuseQuotesButDistinguishCollections() {
        val requests = mutableListOf<String>()
        val batch = PriceQuoteBatch(batched = false, utcToday = { today }) {
            requests += it
            quote
        }
        assertEquals(12.0, batch.get("pets", 7).floor)
        assertSame(batch.get("pets", 7), batch.get("pets", 7))
        assertEquals(12.0, batch.get("buds", 7).floor)
        assertEquals(listOf(
            "/community/data?type=tradeable&collection=pets&id=7",
            "/community/data?type=tradeable&collection=buds&id=7",
        ), requests)
    }

    @Test
    fun snapshotUsesTodaysFloorsAndNeverFallsBackToPerItemRequests() {
        val requests = mutableListOf<String>()
        val response = SflApi.ApiResult.Success(JSONObject("""
            {"reports":{
                "2026-11-17":{"items":{"pets-7":{"floor":100}}},
                "2026-11-18":{"items":{
                    "pets-7":{"floor":45,"listingCount":2},
                    "buds-7":{"floor":3,"listingCount":1},
                    "collectibles-2":{"listingCount":0,"latestSale":100},
                    "wearables-5":{"listingCount":2}
                }}
            }}
        """.trimIndent()))
        val batch = PriceQuoteBatch(batched = true, utcToday = { today }) {
            requests += it
            response
        }
        assertEquals(45.0, batch.get("pets", 7).floor)
        assertEquals(3.0, batch.get("buds", 7).floor)
        assertTrue(batch.get("collectibles", 2).valid)
        assertNull(batch.get("collectibles", 2).floor)
        assertTrue(batch.get("pets", 8).valid)
        assertNull(batch.get("pets", 8).floor)
        assertFalse(batch.get("wearables", 5).valid)
        assertEquals(listOf("/community/data?type=marketplaceActivity"), requests)
        assertFalse(PriceQuoteBatch(true, { today.plusDays(1) }) { response }.get("pets", 7).valid)
    }

    @Test
    fun completedQuotesAreKeptAndTheLimitedRequestIsRetriedInPlace() {
        var time = 0L
        val limiter = CommunityRateLimiter({ time }, { time += it })
        val paths = mutableListOf<Pair<String, Long>>()
        val completed = mutableListOf<String>()
        val progress = mutableListOf<Pair<Long?, List<String>>>()
        val retry = CommunityRateLimiter.Retry(200_000L, { false }) { progress += it to completed.toList() }
        var budsAttempts = 0
        val batch = PriceQuoteBatch(false, { today }) { path ->
            limiter.execute(retry, { 0L }, {}) {
                paths += path.substringAfter("collection=").substringBefore("&") to time
                if (path.contains("collection=buds") && budsAttempts++ == 0) {
                    CommunityRateLimiter.Response(SflApi.ApiResult.Throttled, 30_000L)
                } else CommunityRateLimiter.Response(quote)
            }
        }
        for (collection in listOf("pets", "buds", "wearables", "pets")) {
            assertEquals(12.0, batch.get(collection, 7).floor)
            completed += collection
        }
        assertEquals(listOf("pets" to 0L, "buds" to 5_500L, "buds" to 35_500L, "wearables" to 41_000L), paths)
        assertEquals(listOf(30_000L to listOf("pets"), null to listOf("pets")), progress)
    }

    @Test
    fun aSecond429StopsAndSharesTheLongerServerCooldownWithOtherCallers() {
        var time = 0L
        var savedCooldown = 0L
        val limiter = CommunityRateLimiter({ time }, { time += it })
        val calls = mutableListOf<Long>()
        val progress = mutableListOf<Long?>()
        val retry = CommunityRateLimiter.Retry(120_000L, { false }) { progress += it }
        val result = limiter.execute(retry, { 0L }, { savedCooldown = it }) {
            calls += time
            CommunityRateLimiter.Response(SflApi.ApiResult.Throttled, if (calls.size == 1) 45_000L else 60_000L)
        }
        assertSame(SflApi.ApiResult.Throttled, result)
        assertEquals(listOf(0L, 45_000L), calls)
        assertEquals(listOf(45_000L, null), progress)
        val refreshed = { CommunityRateLimiter.Response(quote) }
        assertSame(SflApi.ApiResult.Throttled, limiter.execute(null, { 0L }, {}, refreshed))
        val afterRestart = CommunityRateLimiter({ time }, { time += it })
        assertSame(SflApi.ApiResult.Throttled, afterRestart.execute(null, { savedCooldown }, {}, refreshed))
        time += 60_000L
        assertSame(quote, afterRestart.execute(null, { 0L }, {}, refreshed))
    }

    @Test
    fun longCooldownIsNotCutShortAndCancellationPreventsSending() {
        var time = 0L
        val limiter = CommunityRateLimiter({ time }, { time += it })
        val return429 = { CommunityRateLimiter.Response(SflApi.ApiResult.Throttled, 600_000L) }
        val short = CommunityRateLimiter.Retry(200_000L, { false }) { error("Wait exceeds deadline") }
        assertSame(SflApi.ApiResult.Throttled, limiter.execute(short, { 0L }, {}, return429))
        assertEquals(0L, time)

        val progress = mutableListOf<Long?>()
        val cancelled = CommunityRateLimiter.Retry(700_000L, { time >= 1_000L }) { progress += it }
        try {
            limiter.execute(cancelled, { 0L }, {}) { error("Must not send after cancellation") }
            error("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(1_000L, time)
            assertEquals(listOf(600_000L, null), progress)
        }
    }

    @Test
    fun rateLimitCooldownRespectsServerHeadersWithoutShorteningNormalSpacing() {
        val now = Instant.parse("2026-11-18T19:00:00Z").toEpochMilli()
        assertEquals(60_000L, SflApi.retryAfterMillis("60", now))
        assertEquals(30_000L, SflApi.retryAfterMillis("Wed, 18 Nov 2026 19:00:30 GMT", now))
        assertEquals(30_000L, SflApi.retryAfterMillis("1", now))
        assertEquals(30_000L, SflApi.retryAfterMillis("invalid", now))
        assertEquals(30_000L, SflApi.retryAfterMillis(null, now))
    }
}
