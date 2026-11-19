package com.sfl.browser.notify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResourceYieldsTest {
    private val now = 100_000_000L
    private val farmId = 1234L

    private fun farm(json: String): YieldFarm {
        val state = JSONObject(json)
        return YieldFarm(state, CropGrowth(state), now)
    }

    private fun node(group: String, id: String, json: String, at: Long = now) =
        ResourceYields.Node(group, id, JSONObject(json), at)

    @Test
    fun storedAmountsDoNotAdvanceCounterAndSortingControlsRolls() {
        val state = farm("""{"bumpkin":{"skills":{}},"inventory":{},
            "farmActivity":{"Basic Tree Chopped":5}}""")
        val results = ResourceYields.predict(state, farmId, listOf(
            node("trees", "later", """{"wood":{}}""", now + 200),
            node("trees", "stored", """{"wood":{"amount":9}}""", now + 100),
            node("trees", "earlier", """{"wood":{}}""", now + 10),
            node("trees", "zero", """{"wood":{"amount":0}}""", now + 50),
        )).getValue("trees")
        assertEquals(1.0, results.getValue("earlier")!!, 0.00001)
        assertEquals(2.0, results.getValue("later")!!, 0.00001)
        assertEquals(9.0, results.getValue("stored")!!, 0.00001)
        assertEquals(0.0, results.getValue("zero")!!, 0.00001)
    }

    @Test
    fun woodMultipliersApplyBeforeTierAndStrongestBud() {
        val state = farm("""{"bumpkin":{"skills":{"Lumberjack's Extra":3},
            "equipped":{"hand":"Bumpkin Shield"}},"faction":{"name":"bumpkins"},
            "inventory":{"Discord Mod":1,"Lumberjack":1},"farmActivity":{},
            "collectibles":{"Foreman Beaver":[{"coordinates":{"x":1,"y":1}}],
            "Tiki Totem":[{"coordinates":{"x":2,"y":2}}]},
            "buds":{"1":{"type":"Woodlands","stem":"Tree Hat","aura":"Green","coordinates":{}},
            "2":{"type":"Woodlands","aura":"Rare","coordinates":{}}}}""")
        val result = ResourceYields.predict(state, farmId, listOf(
            node("trees", "1", """{"multiplier":4,"tier":3,"wood":{}}"""),
        )).getValue("trees").getValue("1")!!
        assertEquals((1.2 * 1.35 * 1.1 + 0.2 + 0.1 + 0.25 + 0.48) * 4 + 2.5, result, 0.00001)
    }

    @Test
    fun rockSkillRanksIslandBudAndVariantsUseTheirOwnCounters() {
        val state = farm("""{"bumpkin":{"skills":{"Rocky Favor":2,"Ferrous Favor":3,
             "Rock'N'Roll":2,"Iron Bumpkin":3,"Golden Touch":2}},
             "island":{"type":"volcano"},"inventory":{"Prospector":1,"Gold Rush":1},
             "farmActivity":{"Stone Rock Mined":7,"Fused Stone Rock Mined":2},
             "buds":{"1":{"type":"Cave","stem":"Diamond Gem","aura":"Rare","coordinates":{}}}}""")
        val nodes = listOf(
            node("stones", "s", """{"stone":{"minedAt":1000}}"""),
            node("stones", "f", """{"name":"Fused Stone Rock","multiplier":4,"tier":2,"stone":{"minedAt":1000}}"""),
            node("iron", "i", """{"tier":3,"stone":{"minedAt":1000}}"""),
            node("gold", "g", """{"stone":{"minedAt":1000}}"""),
        )
        val result = ResourceYields.predict(state, farmId, nodes)
        fun native(item: Int, counter: Int) = if (YieldRoll.forItem(farmId, item, counter).hit("Native", 20.0)) 1.0 else 0.0
        assertEquals(1 + .2 + .15 + 1.4 - .7 + .8 + .1 + native(619, 7), result.getValue("stones").getValue("s")!!, .00001)
        assertEquals((1 + .2 + .15 + 1.4 - .7 + .8 + .1 + native(2700, 2)) * 4 + .5, result.getValue("stones").getValue("f")!!, .00001)
        assertEquals(1 + .2 - .6 + 2 + .8 + .1 + native(620, 0) + 2.5, result.getValue("iron").getValue("i")!!, .00001)
        assertEquals(1 + .5 + .75 + .8 + .1 + native(621, 0), result.getValue("gold").getValue("g")!!, .00001)
    }

    @Test
    fun turtleCooldownTracksSameCellInBatchWithoutMutatingFarm() {
        val state = farm("""{"bumpkin":{"skills":{}},"inventory":{},"farmActivity":{},
            "island":{"type":"basic"},"collectibles":{
            "Emerald Turtle":[{"coordinates":{"x":0,"y":0}}],
            "Tin Turtle":[{"coordinates":{"x":0,"y":0}}]},
            "aoe":{"Emerald Turtle":{"1":{"0":99990000}}}}""")
        val nodes = listOf(
            node("stones", "one", """{"x":1,"y":0,"stone":{"minedAt":99999000,"baseDurationMs":1000}}"""),
            node("stones", "two", """{"x":1,"y":0,"stone":{"minedAt":99999000,"baseDurationMs":1000}}"""),
            node("stones", "far", """{"x":2,"y":0,"stone":{"minedAt":99999000,"baseDurationMs":1000}}"""),
        )
        val result = ResourceYields.predict(state, farmId, nodes).getValue("stones")
        fun native(counter: Int) = if (YieldRoll.forItem(farmId, 619, counter).hit("Native", 20.0)) 1.0 else 0.0
        assertEquals(1.6 + native(0), result.getValue("one")!!, .00001)
        assertEquals(1.0 + native(1), result.getValue("two")!!, .00001)
        assertEquals(1.0 + native(2), result.getValue("far")!!, .00001)
        assertEquals(99990000L, state.farm.getJSONObject("aoe").getJSONObject("Emerald Turtle").getJSONObject("1").getLong("0"))
        assertEquals(result, ResourceYields.predict(state, farmId, nodes).getValue("stones"))
    }

    @Test
    fun areaCooldownBoundaryUsesLiveRecoveryAndFutureReadyTime() {
        val state = farm("""{"bumpkin":{"skills":{}},"inventory":{},"farmActivity":{},
            "island":{"type":"basic"},"collectibles":{"Emerald Turtle":[{"coordinates":{"x":0,"y":0}}]},
            "aoe":{"Emerald Turtle":{"1":{"0":99999600},"0":{"1":99999601}}}}""")
        val at = now + 600
        val result = ResourceYields.predict(state, farmId, listOf(
            node("iron", "ready", """{"x":1,"y":0,"stone":{"minedAt":99999600,"baseDurationMs":2000}}""", at),
            node("iron", "early", """{"x":0,"y":1,"stone":{"minedAt":99999600,"baseDurationMs":2000}}""", at),
        )).getValue("iron")
        val native0 = if (YieldRoll.forItem(farmId, 620, 0).hit("Native", 20.0)) 1.0 else 0.0
        val native1 = if (YieldRoll.forItem(farmId, 620, 1).hit("Native", 20.0)) 1.0 else 0.0
        assertEquals(1.5 + native0, result.getValue("ready")!!, .00001)
        assertEquals(1.0 + native1, result.getValue("early")!!, .00001)
    }

    @Test
    fun unknownInputsAreNotPresentedAsExactButStoredValuesSurvive() {
        val state = farm("""{"bumpkin":{"skills":{}},"inventory":{},"farmActivity":{},"island":{"type":"basic"}}""")
        val nodes = listOf(
            node("gold", "missing-id", """{"stone":{}}"""),
            node("gold", "saved", """{"stone":{"amount":3.2}}"""),
        )
        val result = ResourceYields.predict(state, null, nodes).getValue("gold")
        assertNull(result.getValue("missing-id"))
        assertEquals(3.2, result.getValue("saved")!!, 0.00001)
        assertNull(ResourceYields.predict(state, farmId, listOf(
            node("gold", "unknown", """{"name":"Future Rock","stone":{}}"""),
        )).getValue("gold").getValue("unknown"))
    }
}
