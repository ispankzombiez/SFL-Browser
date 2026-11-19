package com.sfl.browser.notify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FieldCropYieldTest {
    private val now = 1_000_000L

    @Test
    fun eachFieldCropTierUsesItsOwnSkillAndCropBonuses() {
        val farm = JSONObject(
            """{
                "bumpkin":{"skills":{"Young Farmer":1,"Experienced Farmer":2,"Old Farmer":3},
                    "equipped":{"tool":"Sickle"}},
                "collectibles":{"Easter Bunny":[{"coordinates":{"x":0,"y":0}}],
                    "Pablo The Bunny":[{"coordinates":{"x":1,"y":0}}],
                    "Sheaf of Plenty":[{"coordinates":{"x":2,"y":0}}],
                    "Giant Kale":[{"coordinates":{"x":3,"y":0}}]}
            }""",
        )
        val calculator = calculator(farm)

        assertEquals(1.1, calculator.predict(crop("Sunflower"))!!, 0.00001)
        assertEquals(1.425, calculator.predict(crop("Carrot"))!!, 0.00001)
        assertEquals(1.125, calculator.predict(crop("Saltwort"))!!, 0.00001)
        assertEquals(3.15, calculator.predict(crop("Wheat"))!!, 0.00001)
        assertEquals(3.15, calculator.predict(crop("Kale"))!!, 0.00001)
    }

    @Test
    fun criticalEffectsRequireAndUseTheSuppliedDeterministicRoll() {
        val farm = JSONObject(
            """{
                "bumpkin":{"skills":{},"equipped":{"necklace":"Green Amulet"}},
                "collectibles":{"Peeled Potato":[{"coordinates":{"x":0,"y":0}}]}
            }""",
        )
        val calculator = calculator(farm)

        assertNull(calculator.predict(crop("Potato")))
        val hit = YieldRoll { _, _ -> true }
        val miss = YieldRoll { _, _ -> false }
        assertEquals(11.0, calculator.predict(crop("Potato"), roll = hit)!!, 0.00001)
        assertEquals(1.0, calculator.predict(crop("Potato"), roll = miss)!!, 0.00001)
    }

    @Test
    fun cropMachineCanUseTheSameCalculatorWithoutPlotOnlyBonuses() {
        val farm = JSONObject(
            """{
                "bumpkin":{"skills":{"Old Farmer":2,"Laurie's Gains":3}},
                "collectibles":{"Laurie the Chuckle Crow":[{"coordinates":{"x":0,"y":0}}],
                    "Sheaf of Plenty":[{"coordinates":{"x":1,"y":0}}]}
            }""",
        )

        assertEquals(3.125, calculator(farm).predict(crop("Barley"), plot = null)!!, 0.00001)
    }

    @Test
    fun seasonalWearablesAndCropSpecificBonusesStackAfterMultipliers() {
        val farm = JSONObject(
            """{
                "season":{"season":"winter"},
                "bumpkin":{"skills":{},"equipped":{"amulet":"Beetroot Amulet",
                    "tool":"Corn Silk Hair","onesie":"Corn Onesie","heart":"Frozen Heart"}},
                "collectibles":{"Golden Cauliflower":[{"coordinates":{"x":0,"y":0}}],
                    "Giant Onion":[{"coordinates":{"x":1,"y":0}}]}
            }""",
        )
        val calculator = calculator(farm)

        assertEquals(3.0, calculator.predict(crop("Cauliflower"))!!, 0.00001)
        assertEquals(2.2, calculator.predict(crop("Beetroot"))!!, 0.00001)
        assertEquals(5.0, calculator.predict(crop("Onion"))!!, 0.00001)
        assertEquals(3.1, calculator.predict(crop("Corn"))!!, 0.00001)
    }

    @Test
    fun onlyThePriorityCalendarEventChangesCropYield() {
        val farm = JSONObject(
            """{
                "season":{"season":"spring"},
                "calendar":{"bountifulHarvest":{"startedAt":900000},
                    "insectPlague":{"startedAt":950000}},
                "collectibles":{"Spring Guardian":[{"coordinates":{"x":0,"y":0}}]},
                "bumpkin":{"skills":{}}
            }""",
        )

        assertEquals(3.0, calculator(farm).predict(crop("Potato"))!!, 0.00001)
    }

    @Test
    fun plotBonusesAndAoECooldownsAreAppliedPerHarvestedCell() {
        val farm = JSONObject(
            """{
                "bumpkin":{"skills":{"Pollen Power Up":1}},
                "collectibles":{"Knowledge Crab":[{"coordinates":{"x":0,"y":0}}],
                    "Sir Goldensnout":[{"coordinates":{"x":4,"y":4}}]},
                "aoe":{"Sir Goldensnout":{"0":{"0":990000}}}
            }""",
        )
        val calculator = calculator(farm)
        val plot = JSONObject("""{"x":4,"y":4,"crop":{"name":"Barley","plantedAt":990000},
            "fertiliser":{"name":"Sprout Mix"},"beeSwarm":{"count":2}}""")

        assertEquals(2.5, calculator.predict(plot.getJSONObject("crop"), plot, now)!!, 0.00001)
        assertEquals(2.0, calculator.predict(plot.getJSONObject("crop"), plot, now)!!, 0.00001)
    }

    @Test
    fun barleyCompatibilityApiPreservesTheKnown69PlotRegression() {
        val farm = JSONObject(
            """{
                "bumpkin":{"skills":{"Old Farmer":1,"Acre Farm":1,"Hectare Farm":1,"Laurie's Gains":1}},
                "home":{"collectibles":{"Scarecrow":[{"coordinates":{"x":0,"y":0}}]}},
                "collectibles":{"Laurie the Chuckle Crow":[{"coordinates":{"x":0,"y":0}}]},
                "buds":{"a":{"type":"Snow","aura":"Green","coordinates":{"x":2,"y":2}},
                    "b":{"stem":"3 Leaf Clover","aura":"Mythical"}}
            }""",
        )
        val readyAt = now + 1_000L
        val calculator = BarleyYield(YieldFarm(farm, CropGrowth(farm), now))
        val plots = (-3..3).flatMap { x -> (-7..-1).map { y -> x to y } } +
            (1..20).map { y -> 0 to y }
        val amounts = plots.mapIndexed { index, (x, y) ->
            val plot = JSONObject().put("x", x).put("y", y).put(
                "crop",
                JSONObject().put("name", "Barley").put("plantedAt", now),
            )
            calculator.amount(plot, readyAt, null)
        }

        assertEquals(49, amounts.count { it == 2.46 })
        assertEquals(163.74, amounts.filterNotNull().sum(), 0.00001)
    }

    @Test
    fun storedAmountOverridesRulesAndUnknownCropNamesStayUnknown() {
        val noSkills = YieldFarm(JSONObject(), CropGrowth(JSONObject()), now)
        val calculator = FieldCropYieldCalculator(noSkills)

        assertEquals(4.5, calculator.predict(crop("Barley", 4.5))!!, 0.00001)
        assertNull(calculator.predict(crop("Unrecognized Crop")))
    }

    private fun crop(name: String, amount: Double? = null): JSONObject =
        JSONObject().put("name", name).also { if (amount != null) it.put("amount", amount) }

    private fun calculator(farm: JSONObject) =
        FieldCropYieldCalculator(YieldFarm(farm, CropGrowth(farm), now))
}
