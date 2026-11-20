package com.sfl.browser.notify

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class FarmDetailsTest {
    private val now = Instant.parse("2026-11-19T13:00:00Z").toEpochMilli()

    @Test
    fun placedProjectsCrossTheirGoalOnlyOncePerObservedTransition() {
        val farm = JSONObject("""{
            "collectibles":{"Big Orange":[{"coordinates":{"x":1,"y":2}}],"Big Apple":[{}],
                "Basic Cooking Pot":[{"coordinates":{"x":2,"y":2}}]},
            "socialFarming":{"villageProjects":{"Big Orange":{"cheers":24},"Big Apple":{"cheers":50}},
                "completedProjects":["Basic Cooking Pot"]}
        }""")
        val details = FarmDetails.extract(farm, now)
        val bars = details.optJSONArray("projects").farmObjects().associate { it.getString("name") to it.getInt("cheers") }
        assertEquals(mapOf("Big Orange" to 24, "Basic Cooking Pot" to 10), bars)
        val before = FarmDetails.pollState(farm, details, now).getJSONObject("projects")
        farm.getJSONObject("socialFarming").getJSONObject("villageProjects").getJSONObject("Big Orange").put("cheers", 26)
        val after = FarmDetails.pollState(farm, FarmDetails.extract(farm, now), now).getJSONObject("projects")
        assertTrue(FarmPollAlerts.reached(null, after).isEmpty())
        assertEquals(listOf("Big Orange"), FarmPollAlerts.reached(before, after))
        assertTrue(FarmPollAlerts.reached(after, after).isEmpty())
    }

    @Test
    fun taskStatusUsesCompletionAndSeparatesAnimalBounties() {
        val farm = JSONObject("""{
            "delivery":{"orders":[{"from":"Betty","items":{"Orange":4}},{"from":"Bert","completedAt":1}]},
            "choreBoard":{"chores":{"raven":{"name":"Plant flowers","completedAt":1}}},
            "chores":{"chores":{"1":{"description":"Catch fish"}}},
            "kingdomChores":{"chores":[{"description":"Skipped","skippedAt":1}]},
            "bounties":{"requests":[{"name":"Chicken","level":4,"id":"a"},{"name":"Anchovy","id":"b"}],
                "completed":[{"id":"a","soldAt":1}]}
        }""")
        val tasks = FarmDetails.extract(farm, now).optJSONArray("tasks").farmObjects().toList()
        assertEquals(listOf("Betty", "Catch fish", "Anchovy"),
            tasks.filterNot { it.getBoolean("done") }.map { it.getString("name") })
        assertEquals(listOf("Bert", "Plant flowers", "Chicken"),
            tasks.filter { it.getBoolean("done") }.map { it.getString("name") })
        assertEquals("animalBounties", tasks.last { it.getBoolean("done") }.getString("group"))
    }

    @Test
    fun factionStatusUsesCurrentWeekAndPetNeglectUsesCareAtUtcDayBoundaries() {
        val farm = JSONObject("""{
            "pets":{"common":{"a":{"name":"Barkley","experience":1,"requests":{"fedAt":${now - FarmDay.DAY_MS}},
                "cheeredAt":$now}},"nfts":{"b":{"name":"Pet #2","experience":1,"requests":{"fedAt":$now}}}},
            "faction":{
                "kitchen":{"week":"2026-11-16","requests":[{"item":"Honey","dailyFulfilled":{"4":1}},
                    {"item":"Oil","dailyFulfilled":{"3":1}}]},
                "pet":{"week":"2026-11-09","requests":[{"food":"Cake","dailyFulfilled":{"4":1}}]}
            }
        }""")
        val details = FarmDetails.extract(farm, now)
        assertEquals(listOf("Honey" to true, "Oil" to false),
            details.optJSONArray("daily").farmObjects().map { it.getString("name") to it.getBoolean("done") }.toList())
        assertEquals(listOf(FarmDay.start(now) + 4 * FarmDay.DAY_MS, FarmDay.start(now) + 8 * FarmDay.DAY_MS),
            details.optJSONArray("pets").farmObjects().map { it.getLong("neglectAt") }.toList())
    }

    @Test
    fun factionPetStaysDoneForTheWeekWhileKitchenResetsDaily() {
        val farm = JSONObject("""{
            "faction":{
                "kitchen":{"week":"2026-11-16","requests":[{"item":"Pumpkin","dailyFulfilled":{"4":10}}]},
                "pet":{"week":"2026-11-16","requests":[
                    {"food":"Fried Tofu","dailyFulfilled":{"1":1}},
                    {"food":"Sour Shake","quantity":2,"dailyFulfilled":{"1":1}},
                    {"food":"Surimi Rice Bowl","dailyFulfilled":{"1":1}},
                    {"food":"Lumber Doll","dailyFulfilled":{"1":1}}
                ]}
            }
        }""")
        fun statusesAt(at: String) = FarmDetails.extract(farm, Instant.parse(at).toEpochMilli())
            .getJSONArray("daily").farmObjects().associate { it.getString("name") to it.getBoolean("done") }
        val petDone = mapOf("Fried Tofu" to true, "Sour Shake" to true, "Surimi Rice Bowl" to true, "Lumber Doll" to true)
        assertEquals(petDone + ("Pumpkin" to true), statusesAt("2026-11-19T23:59:59Z"))
        assertEquals(petDone + ("Pumpkin" to false), statusesAt("2026-11-20T00:00:00Z"))
        assertEquals(petDone + ("Pumpkin" to false), statusesAt("2026-11-22T23:59:59Z"))
        val monday = "2026-11-23T00:00:00Z"
        assertTrue(statusesAt(monday).isEmpty())

        farm.getJSONObject("faction").put("pet", JSONObject("""{
            "week":"2026-11-23","requests":[{"food":"Fried Tofu","dailyFulfilled":{}}]
        }"""))
        assertEquals(mapOf("Fried Tofu" to false), statusesAt(monday))
    }

    @Test
    fun remindersUseUtcForDateOnlyStartsAndNeverTreatEndsAsStarts() {
        val start = Instant.parse("2026-11-20T00:00:00Z").toEpochMilli()
        val farm = JSONObject("""{
            "vip":{"expiresAt":${start + 2000}},
            "calendar":{"dates":[{"name":"tornado","date":"2026-11-20","weather":true},
                {"name":"calendar","title":"Town Hall","date":"2026-11-20T18:00:00Z"},
                {"name":"tornado","date":"invalid","weather":true}]},
            "specialEvents":{"current":{"Party":{"isEligible":true,"startAt":$start,"endAt":${start + 9000}},
                "Hidden":{"isEligible":false,"startAt":$start}}}
        }""")
        val reminders = FarmReminders.extract(farm).farmObjects().associate { it.getString("kind") to it.getLong("at") }
        assertEquals(mapOf("vip" to start + 2000, "weather" to start, "calendar" to start + 18 * 3600000,
            "special" to start), reminders)
        assertFalse(start - 600001 in FarmReminders.window(start, 10))
        assertTrue(start - 600000 in FarmReminders.window(start, 10))
        assertFalse(start in FarmReminders.window(start, 10))
    }

    @Test
    fun dailyResetDoesNotDeliverEarlyTwiceOrOnAnotherDay() {
        val midnight = FarmDay.nextReset(now)
        assertFalse(DailyResetAlarm.shouldDeliver(midnight, midnight - 1, 0))
        assertTrue(DailyResetAlarm.shouldDeliver(midnight, midnight, 0))
        assertFalse(DailyResetAlarm.shouldDeliver(midnight, midnight + 1000, midnight))
        assertFalse(DailyResetAlarm.shouldDeliver(midnight, midnight + FarmDay.DAY_MS, 0))
    }

    @Test
    fun sicknessAlertsOnlyForNewCasesInAlreadyObservedHerds() {
        val before = JSONObject("""{"barn":{"a":"Cow"},"henHouse":{}}""")
        val after = JSONObject("""{"barn":{"a":"Cow","b":"Sheep"},"henHouse":{"c":"Chicken"},"pigpen":{"d":"Pig"}}""")
        assertEquals(mapOf("Sheep" to 1, "Chicken" to 1), FarmPollAlerts.newlySick(before, after))
    }
}
