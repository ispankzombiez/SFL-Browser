package com.sfl.browser.notify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimalYieldsTest {
    private fun farm(json: String = """{"bumpkin":{"skills":{}}}"""): YieldFarm {
        val data = JSONObject(json)
        return YieldFarm(data, CropGrowth(data), 1_000_000L)
    }

    private fun animal(type: String, xp: Number, state: String = "ready"): JSONObject =
        JSONObject().put("type", type).put("experience", xp).put("state", state)

    @Test
    fun readyUsesCurrentLevelOthersUseNextAndCapAtMaximum() {
        val farm = farm()
        assertEquals(emptyMap<String, Double>(), AnimalYields.predict(animal("Chicken", 59), farm))
        assertEquals(mapOf("Egg" to 1.0), AnimalYields.predict(animal("Chicken", 59, "idle"), farm))
        assertEquals(mapOf("Egg" to 1.0, "Feather" to 1.0),
            AnimalYields.predict(animal("Chicken", 240), farm))
        assertEquals(mapOf("Egg" to 5.0, "Feather" to 3.0),
            AnimalYields.predict(animal("Chicken", 100_000, "happy"), farm))
        assertEquals(mapOf("Milk" to 4.0, "Leather" to 4.0),
            AnimalYields.predict(animal("Cow", 8160, "sad"), farm))
        assertEquals(mapOf("Wool" to 2.0, "Merino Wool" to 2.0),
            AnimalYields.predict(animal("Sheep", 1320), farm))
        assertEquals(mapOf("Truffle" to 3.0), AnimalYields.predict(animal("Pig", 3060), farm))
    }

    @Test
    fun multiplicativeBonusesPrecedeFlatPerProductBonuses() {
        val state = farm("""{
            "bumpkin":{"skills":{"Abundant Harvest":2,"Fine Fibers":3,"Featherweight":2,
                "Merino Whisperer":1,"Double Bale":3},
                "equipped":{"hat":"Chicken Suit","coat":"Cattlegrim"}},
            "inventory":{"Barn Manager":1},
            "collectibles":{"Bale":[{"coordinates":{"x":0,"y":0}}],
                "Chicken Coop":[{"coordinates":{"x":0,"y":0}}]},
            "buds":{"1":{"type":"Retreat","stem":"Egg Head","aura":"Green","coordinates":{"x":1,"y":1}},
                "2":{"type":"Retreat","aura":"Mythical"}}
        }""")
        val chicken = animal("Chicken", 240).put("multiplier", 2)
            .put("feedBuff", JSONObject().put("name", "Salt Lick"))

        assertEquals(mapOf("Egg" to 4.58, "Feather" to 4.24),
            AnimalYields.predict(chicken, state))
    }

    @Test
    fun pigMultiplierAndBaleEconomyAreResourceSpecific() {
        val state = farm("""{
            "bumpkin":{"skills":{"Truffle Yield":3,"Bale Economy":1,"Double Bale":2}},
            "collectibles":{"Bale":[{"coordinates":{"x":0,"y":0}}]}
        }""")
        val pig = animal("Pig", 1080).put("multiplier", 2)
            .put("feedBuff", JSONObject().put("name", "Salt Lick"))
        assertEquals(mapOf("Truffle" to 5.46), AnimalYields.predict(pig, state))
        assertEquals(mapOf("Milk" to 2.25, "Leather" to 1.0),
            AnimalYields.predict(animal("Cow", 1080), state))
    }

    @Test
    fun sickAndUnknownInputsAreNotPredicted() {
        val state = farm()
        assertEquals(emptyMap<String, Double>(), AnimalYields.predict(animal("Cow", 180, "sick"), state))
        assertNull(AnimalYields.predict(animal("Unknown", 180), state))
        assertNull(AnimalYields.predict(animal("Cow", -1), state))
        assertNull(AnimalYields.predict(animal("Cow", 180, "mystery"), state))
        assertNull(AnimalYields.predict(animal("Cow", 180), farm("{}")))
    }
}
