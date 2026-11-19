package com.sfl.browser.notify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FarmTimerCacheTest {
    private val savedAt = "2026-11-18T19:00:00Z"

    @Test
    fun persistedRevisionReusesTimersOnlyForTheSameSaveFarmAndCookingMode() {
        val response = JSONObject("""{"updatedAt":"2026-11-18T19:00:00.000Z","farm":{}}""")
        val revision = FarmTimerRevision("123", 0, savedAt, 1000, 9000)
        val restored = FarmTimerRevision.fromJson(JSONObject(revision.toJson().toString()))!!
        assertTrue(restored.canReuse("123", 0, FarmTimerRevision.updatedAt(response), 8999))
        assertFalse(restored.canReuse("123", 0, "2026-11-18T19:00:01Z", 2000))
        assertFalse(restored.canReuse("456", 0, savedAt, 2000))
        assertFalse(restored.canReuse("123", 1, savedAt, 2000))
        assertFalse(restored.canReuse("123", 0, savedAt, 999))
        assertFalse(restored.canReuse("123", 0, savedAt, 9000))
        assertNull(FarmTimerRevision.fromJson(revision.toJson().apply { remove("schema") }))
    }

    @Test
    fun missingOrUnusableTimestampCannotReuseAnOlderSnapshot() {
        val revision = FarmTimerRevision("123", 0, savedAt, 1000, 9000)
        for (body in listOf(
            """{"farm":{"updatedAt":"$savedAt"}}""",
            """{"updatedAt":1700000000000}""",
            """{"updatedAt":"not a timestamp"}""",
        )) {
            val updatedAt = FarmTimerRevision.updatedAt(JSONObject(body))
            assertNull(updatedAt)
            assertFalse(revision.canReuse("123", 0, updatedAt, 2000))
        }
    }

    @Test
    fun ordinaryCompletionDoesNotRequireExtractingTheFarmAgain() {
        val snapshot = extract("""{"craftingBox":{"queue":[{"id":"1","name":"Chair","readyAt":3000}]}}""", 1000)
        assertEquals(Long.MAX_VALUE, snapshot.refreshAt)
        val revision = FarmTimerRevision("123", 0, savedAt, 1000, snapshot.refreshAt)
        assertTrue(revision.canReuse("123", 0, savedAt, 3000))
        assertTrue(planTimerNotifications(snapshot.timers, 0, 0, now = 2999) { _, _ -> false }
            .none { it.fireAt <= 2999 })
        assertEquals("Chair", planTimerNotifications(snapshot.timers, 0, 0, now = 3000) { _, _ -> false }
            .single { it.fireAt <= 3000 }.timers.single().label)
    }

    @Test
    fun expiredYieldBonusInvalidatesTheSameFarmSaveOnTime() {
        val farm = """{
            "bumpkin":{"skills":{}},
            "crimstones":{"1":{"minesLeft":1,"stone":{"minedAt":1000,"baseDurationMs":86400000}}}
        }"""
        val expires = 172_801_001L
        val snapshot = extract(farm, expires - 2)
        assertEquals(expires, snapshot.refreshAt)
        assertEquals(3.0, snapshot.timers.single().yieldAmount)
        assertEquals(1.0, extract(farm, expires).timers.single().yieldAmount)
    }

    @Test
    fun dynamicFarmActionsSetTheirOwnExpiry() {
        val snapshot = extract("""{
            "henHouse":{"animals":{"1":{"type":"Chicken","state":"idle","item":"Apple",
                "asleepAt":1000,"awakeAt":4000}}},
            "buds":{"1":{"type":"Snow"}}
        }""", 2000)
        assertEquals(4000L, snapshot.refreshAt)
        assertTrue(snapshot.timers.any { it.key.startsWith("animalLove:") })

        val midnight = Instant.parse("2026-11-19T00:00:00Z").toEpochMilli()
        val buds = extract("""{"buds":{"1":{"type":"Snow"}}}""", midnight - 1)
        assertEquals(midnight, buds.refreshAt)
    }

    private fun extract(farm: String, now: Long) =
        FarmTimers.extractSnapshot(JSONObject(farm), 0, 123, now) { null }
}
