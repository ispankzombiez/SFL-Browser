package com.sfl.browser.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineEventsTest {
    @Test
    fun raffleExpansionKeepsEveryPlaceWithQuantitiesWearablesAndNfts() {
        val prizes = TimelineEvents.rafflePrizes(JSONObject("""{
            "10":{"items":{"Wood":2,"Stone":14}},
            "3":{"nft":"Pet #42"},
            "1":{"wearables":{"Acorn Hat":1}},
            "2":{"items":{"Gold":"5","Iron":12}}
        }"""))
        assertEquals(listOf(1, 2, 3, 10), prizes.map { it.place })
        assertEquals(listOf("Acorn Hat" to "1", "Gold" to "5", "Iron" to "12",
            "Pet #42" to null, "Wood" to "2", "Stone" to "14"),
            prizes.flatMap { it.rewards }.map { it.name to it.amount })
    }
}
