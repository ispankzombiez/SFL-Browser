package com.sfl.browser.notify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CropGrowthTest {
    @Test
    fun resourcePredictionAppliesOnlyTheRecoveryBoostsForItsFamily() {
        val farm = JSONObject("""{"boostHistory":{
            "Badger Shrine":[{"from":1000,"to":5000}],
            "Ore Hourglass":[{"from":1000,"to":5000}]
        }}""")
        val growth = CropGrowth(farm)
        assertEquals(2000L, growth.resourcePredictionReadyAt("trees", 1000, 1350))
        assertEquals(1500L, growth.resourcePredictionReadyAt("stones", 1000, 1350))
        assertEquals(1675L, growth.resourcePredictionReadyAt("iron", 1000, 1350))
        assertEquals(1675L, growth.resourcePredictionReadyAt("gold", 1000, 1350))
    }

    @Test
    fun barleyUsesThePlotFertiliserAndStacksWithSparrowShrine() {
        val farm = JSONObject("""{"collectibles":{"Sparrow Shrine":[{"createdAt":1794934757168}]}}""")
        val plot = JSONObject("""{
            "crop":{"name":"Barley","plantedAt":1794968086182,"baseDurationMs":101721744},
            "fertiliser":{"name":"Rapid Root","fertilisedAt":1794968101156}
        }""")

        assertEquals(1795005768389L, CropGrowth(farm).plotReadyAt(plot.getJSONObject("crop"), plot))
    }

    @Test
    fun overlappingTotemsCountOnceAndGrowthReturnsToNormalWhenTheyExpire() {
        val farm = JSONObject("""{"boostHistory":{
            "Super Totem":[{"from":1000,"to":1600}],
            "Time Warp Totem":[{"from":1400,"to":1800}]
        }}""")
        val plant = JSONObject("""{"name":"Rice","plantedAt":1100,"baseDurationMs":1700}""")

        assertEquals(2100L, CropGrowth(farm).greenhouseReadyAt(plant, JSONObject()))
    }

    @Test
    fun greenhouseGlowStacksWithTheHourglassForThatPlant() {
        val farm = JSONObject("""{"boostHistory":{"Harvest Hourglass":[{"from":1000,"to":10000}]}}""")
        val pot = JSONObject("""{"fertiliser":{"name":"Greenhouse Glow","fertilisedAt":1000}}""")
        val rice = JSONObject("""{"name":"Rice","plantedAt":1000,"baseDurationMs":2700}""")
        val grape = JSONObject("""{"name":"Grape","plantedAt":1000,"baseDurationMs":2700}""")

        assertEquals(2600L, CropGrowth(farm).greenhouseReadyAt(rice, pot))
        assertEquals(3160L, CropGrowth(farm).greenhouseReadyAt(grape, pot))
    }
}
