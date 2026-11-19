package com.sfl.browser.notify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantYieldsTest {
    private val now = 1_000_000L

    @Test
    fun patchFruitCombinesFixedBonusesFertiliserWearableAndBestBud() {
        val farm = JSONObject(
            """{
                "bumpkin":{"skills":{"Fruitful Fumble":2,"Fruitful Bounty":3},
                    "equipped":{"coat":"Fruit Picker Apron","onesie":"Camel Onesie"}},
                "collectibles":{"Lady Bug":[{"coordinates":{"x":0,"y":0}}]},
                "buds":{"1":{"type":"Beach","stem":"Banana","aura":"Rare",
                    "coordinates":{"x":1,"y":1}}}
            }""",
        )
        val state = state(farm)

        assertEquals(
            2.8,
            PlantYields.fruit(
                JSONObject("""{"name":"Apple"}"""),
                "Fruitful Blend",
                state,
            )!!,
            0.00001,
        )
    }

    @Test
    fun randomFruitBonusRequiresAndUsesTheSeededHarvestRoll() {
        val state = state(JSONObject("""{"bumpkin":{"skills":{"Generous Orchard":2}}}"""))
        val apple = JSONObject("""{"name":"Apple"}""")

        assertNull(PlantYields.fruit(apple, null, state))
        assertEquals(
            2.0,
            PlantYields.fruit(apple, null, state, YieldRoll { name, chance ->
                name == "Generous Orchard" && chance == 30.0
            })!!,
            0.00001,
        )
        assertEquals(
            1.0,
            PlantYields.fruit(apple, null, state, YieldRoll { _, _ -> false })!!,
            0.00001,
        )
    }

    @Test
    fun greenhouseRiceIncludesCropAndGreenhouseBonuses() {
        val farm = JSONObject(
            """{
                "bumpkin":{"skills":{"Glass Room":1,"Seeded Bounty":2,"Greasy Plants":3},
                    "equipped":{"hat":"Non La Hat"}},
                "collectibles":{"Rice Panda":[{"coordinates":{"x":0,"y":0}}]},
                "buds":{"1":{"stem":"3 Leaf Clover","aura":"Basic",
                    "coordinates":{"x":1,"y":1}}},
                "buffs":{"Power hour":{"startedAt":900000,"durationMS":200000}}
            }""",
        )
        val expected = 1.0 + 0.2 + 1.0 + 0.25 + 0.525 + 0.1 + 0.75 + 2.0 + 0.2

        assertEquals(
            expected,
            PlantYields.greenhouse(
                JSONObject("""{"name":"Rice","plantedAt":1,"baseDurationMs":1}"""),
                "Greenhouse Goodie",
                state(farm),
            )!!,
            0.00001,
        )
    }

    @Test
    fun greenhouseGrapeUsesFruitBonusesWithoutPatchOnlyBonuses() {
        val farm = JSONObject(
            """{
                "bumpkin":{"skills":{"Glass Room":1,"Fruitful Fumble":3},
                    "equipped":{"pants":"Grape Pants","coat":"Fruit Picker Apron"}},
                "collectibles":{"Vinny":[{"coordinates":{"x":0,"y":0}}],
                    "Grape Granny":[{"coordinates":{"x":1,"y":0}}]},
                "buds":{"1":{"type":"Beach","aura":"Green","coordinates":{"x":1,"y":1}}}
            }""",
        )

        assertEquals(
            2.99,
            PlantYields.greenhouse(
                JSONObject("""{"name":"Grape"}"""),
                "Greenhouse Goodie",
                state(farm),
            )!!,
            0.00001,
        )
    }

    @Test
    fun greenhouseRiceGetsTheOvernightBonusBeforeInsectPlagueButOliveDoesNot() {
        val farm = JSONObject(
            """{
                "bumpkin":{"skills":{},"equipped":{"hat":"Non La Hat","tool":"Infernal Pitchfork"}},
                "collectibles":{"Hoot":[{"coordinates":{"x":0,"y":0}}]},
                "calendar":{"insectPlague":{"startedAt":900000}}
            }""",
        )
        val state = state(farm)

        assertEquals(3.25, PlantYields.greenhouse(JSONObject("""{"name":"Rice"}"""), null, state))
        assertEquals(2.0, PlantYields.greenhouse(JSONObject("""{"name":"Olive"}"""), null, state))
    }

    @Test
    fun greenhouseStoredAmountsSkipBoostsExceptTheGoodie() {
        val state = state(
            JSONObject("""{"bumpkin":{"skills":{"Greenhouse Gamble":1,"Greasy Plants":3}}}"""),
        )
        val rice = JSONObject("""{"name":"Rice"}""")

        assertNull(PlantYields.greenhouse(rice, null, state))
        assertEquals(
            4.0,
            PlantYields.greenhouse(rice, null, state, YieldRoll { name, chance ->
                name == "Greenhouse Gamble" && chance == 30.0
            })!!,
            0.00001,
        )
        assertEquals(
            3.2,
            PlantYields.greenhouse(
                JSONObject("""{"name":"Rice","amount":3.0}"""),
                "Greenhouse Goodie",
                state,
                YieldRoll { _, _ -> error("Stored amount does not roll") },
            )!!,
            0.00001,
        )
        assertEquals(
            3.4,
            PlantYields.greenhouse(
                JSONObject("""{"name":"Rice","amount":0}"""),
                "Greenhouse Goodie",
                state,
                YieldRoll { _, _ -> false },
            )!!,
            0.00001,
        )
    }

    @Test
    fun seededRollIsStableAndUsesTheHarvestCounter() {
        val first = YieldRoll.forHarvest(1, "Apple", 0)!!
        val same = YieldRoll.forHarvest(1, "Apple", 0)!!
        val differentCounter = YieldRoll.forHarvest(1, "Apple", 3)!!
        assertEquals(first.hit("Generous Orchard", 50.0), same.hit("Generous Orchard", 50.0))
        assertTrue(first.hit("Generous Orchard", 100.0))
        assertFalse(first.hit("Generous Orchard", 0.0))
        assertTrue(first.hit("Generous Orchard", 50.0))
        assertFalse(differentCounter.hit("Generous Orchard", 50.0))
        assertNull(YieldRoll.forHarvest(1, "not-a-crop", 0))
    }

    @Test
    fun batchRollsStartAtFarmActivityAndAdvanceOnlyForThatFruit() {
        val farm = JSONObject("""{"farmActivity":{"Apple Harvested":3}}""")
        val rolls = HarvestRolls(farm, 1)
        val first = rolls.next("Apple")!!
        rolls.next("Blueberry")
        val second = rolls.next("Apple")!!
        for (chance in 1..99) {
            assertEquals(
                YieldRoll.forHarvest(1, "Apple", 3)!!.hit("Generous Orchard", chance.toDouble()),
                first.hit("Generous Orchard", chance.toDouble()),
            )
            assertEquals(
                YieldRoll.forHarvest(1, "Apple", 4)!!.hit("Generous Orchard", chance.toDouble()),
                second.hit("Generous Orchard", chance.toDouble()),
            )
        }
        assertNull(HarvestRolls(farm, null).next("Apple"))
    }

    @Test
    fun legendaryShrineMustStillBeActiveAtFruitHarvest() {
        val farm = JSONObject("""{
            "bumpkin":{"skills":{}},
            "collectibles":{"Legendary Shrine":[{"createdAt":900000,"coordinates":{"x":0,"y":0}}]}
        }""")
        val apple = JSONObject("""{"name":"Apple"}""")
        val state = state(farm)
        assertEquals(2.0, PlantYields.fruit(apple, null, state))
        assertEquals(1.0, PlantYields.fruit(apple, null, state, harvestAt = 87_300_000L))
    }

    @Test
    fun storedPatchFruitAmountOverridesBonusesAndFullMoonHairAppliesOnlyToMoonFruit() {
        val state = state(
            JSONObject(
                """{
                    "bumpkin":{"skills":{"Fruitful Fumble":3},"equipped":{"hair":"Moon Hair"}}
                }""",
            ),
        )

        assertEquals(
            2.5,
            PlantYields.fruit(
                JSONObject("""{"name":"Apple","amount":2.5}"""),
                null,
                state,
            )!!,
            0.00001,
        )
        assertEquals(
            1.7,
            PlantYields.fruit(
                JSONObject("""{"name":"Duskberry"}"""),
                null,
                state,
            )!!,
            0.00001,
        )
    }

    private fun state(farm: JSONObject): YieldFarm =
        YieldFarm(farm, CropGrowth(farm), now)
}
