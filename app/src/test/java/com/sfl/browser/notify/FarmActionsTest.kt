package com.sfl.browser.notify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sfl.browser.data.TimerCategory
import com.sfl.browser.overview.TimerUi
import java.time.Instant

class FarmActionsTest {
    @Test
    fun spiceJobsDisplayProductsAndStampedOutputAmountsInsteadOfRackNames() {
        val farm = JSONObject("""{
            "bumpkin":{"skills":{"Ager":3},"equipped":{"onesie":"Salt Bottle Onesie"}},
            "buildings":{"Aging Shed":[{"coordinates":{"x":1,"y":1}}]},
            "agingShed":{"racks":{"spice":[
                {"id":"a","recipe":"Refined Salt","readyAt":3000},
                {"id":"b","recipe":"Honey Treat","readyAt":4000,"skills":{"Ager":true}},
                {"id":"c","recipe":"Truffle Seasoning","readyAt":5000,"skills":{"Ager":2}}
            ]}}
        }""")
        val timers = FarmTimers.fromJson(FarmTimers.toJson(FarmTimers.extract(farm, 0, 123, 2000) { null }))
        assertEquals(
            listOf("Refined Salt" to 2.0, "Honey Treat" to 11.0, "Truffle Seasoning" to 16.0),
            timers.map { it.label to it.yieldAmount },
        )
        assertTrue(timers.all { it.category == TimerCategory.SPICE_RACK })
    }

    @Test
    fun namedAgingAndFermentationJobsScheduleThroughTheFarmTimerPlanner() {
        val farm = JSONObject("""{
            "buildings":{"Aging Shed":[{"coordinates":{"x":1,"y":1}}]},
            "agingShed":{"racks":{
                "aging":[{"id":"a","fish":"Anchovy","readyAt":3000},{"id":"b","fish":"Tuna","readyAt":3200}],
                "fermentation":[{"id":"c","recipe":"Fermented Fish","readyAt":3400}]
            }}
        }""")
        val timers = FarmTimers.fromJson(FarmTimers.toJson(FarmTimers.extract(farm, 0, null, 2000) { null }))
        assertEquals(listOf("Anchovy", "Tuna", "Fermented Fish"), timers.map { it.label })
        assertEquals(listOf(TimerCategory.AGING_RACK, TimerCategory.AGING_RACK, TimerCategory.FERMENTATION_RACK),
            timers.map { it.category })
        val groups = planTimerNotifications(timers, windowMs = 1000, offsetMs = 50) { _, _ -> false }
        assertEquals(listOf(3050L, 3250L, 3450L), groups.map { it.fireAt }.sorted())
    }

    @Test
    fun composterKeepsStoredFertilizerAndIncludesThePredictedWormYield() {
        val farm = JSONObject("""{
            "bumpkin":{"skills":{"Wormy Treat":2,"Composting Overhaul":1,"Composting Revamp":1}},
            "collectibles":{"Straw House":[{"coordinates":{"x":1,"y":1}}]},
            "farmActivity":{"Grub Collected":7},
            "buildings":{"Turbo Composter":[
                {"id":"a","producing":{"items":{"Fruitful Blend":"8"},"readyAt":3000}}
            ]}
        }""")
        val timer = FarmTimers.extract(farm, 0, 123, 2000) { null }.single()
        assertEquals("Fruitful Blend", timer.label)
        assertEquals(8.0, timer.yieldProducts?.get("Fruitful Blend"))
        assertTrue(timer.yieldProducts?.get("Grub") in setOf(8.0, 11.0))
        assertEquals(TimerCategory.DISPLAY_ONLY, timer.category)
    }

    @Test
    fun dailyTasksAppearOnlyWhileAvailableAndExpireAtUtcReset() {
        val midnight = Instant.parse("2026-11-18T00:00:00Z").toEpochMilli()
        val reset = Instant.parse("2026-11-19T00:00:00Z").toEpochMilli()
        val quest = JSONObject("""{"startAt":$midnight,"choices":[]}""")
        val budBox = JSONObject().put("openedAt", midnight - 1)
        val farm = JSONObject()
            .put("buds", JSONObject("""{"a":{"type":"Retreat"},"b":{"type":"Saphiro"},"c":{"type":"Snow"}}"""))
            .put("telegram", JSONObject().put("quest", quest))
            .put("pumpkinPlaza", JSONObject().put("budBox", budBox))
        val tasks = FarmTimers.extract(farm, 0, 123, midnight + 1000) { null }
        assertEquals(setOf("budbox:daily", "quest:telegram"), tasks.map { it.key }.toSet())
        assertTrue(tasks.all {
            it.category == TimerCategory.DISPLAY_ONLY &&
                FarmDay.isVisibleTask(it, reset - 1) && !FarmDay.isVisibleTask(it, reset)
        })
        assertEquals(reset, TimerUi.dailyReset("Daily reset", reset - 1).readyAt)
        assertEquals(reset + 86_400_000, TimerUi.dailyReset("Daily reset", reset).readyAt)

        budBox.put("openedAt", midnight + 500)
        assertEquals("quest:telegram", FarmTimers.extract(farm, 0, 123, midnight + 1000) { null }.single().key)
        assertTrue(FarmTimers.extract(farm, 0, 123, reset) { null }.none { it.key == "quest:telegram" })
    }

    @Test
    fun telegramClaimHidesTheQuestUntilNextUtcResetEvenWithEmptyChoices() {
        val today = Instant.parse("2026-11-20T00:00:00Z").toEpochMilli()
        val reset = FarmDay.nextReset(today)
        val quest = JSONObject("""{"startAt":$today,"choices":[]}""")
        val farm = JSONObject().put("telegram", JSONObject().put("quest", quest))
        fun snapshot(at: Long) = FarmTimers.extractSnapshot(farm, 0, null, at) { null }

        assertEquals("quest:telegram", snapshot(today + 1).timers.single().key)
        quest.put("startAt", reset)
        val claimed = snapshot(reset - 1)
        assertTrue(claimed.timers.isEmpty())
        assertEquals(reset, claimed.refreshAt)
        val next = snapshot(reset).timers.single()
        assertEquals("quest:telegram", next.key)
        assertTrue(FarmDay.isVisibleTask(next, reset))

        quest.remove("startAt")
        assertTrue(snapshot(reset).timers.isEmpty())
    }

    @Test
    fun animalLoveFollowsLiveSleepAndUsesTheCurrentRequestedItem() {
        val animal = JSONObject("""{"asleepAt":1000,"lovedAt":0,"item":"Apple","state":"idle"}""")
        assertEquals(AnimalLove(2001, 1, "Apple"), AnimalLove.next(animal, 4001, 1000))
        animal.put("lovedAt", 2200).put("lovesThisSleep", 1).put("item", "Carrot")
        assertEquals(AnimalLove(3300, 2, "Carrot"), AnimalLove.next(animal, 4300, 2200))
        assertNull(AnimalLove.next(animal, 4300, 4300))
        animal.put("lovesThisSleep", 2)
        assertNull(AnimalLove.next(animal, 5000, 2200))
    }

    @Test
    fun animalLoveNeverGroupsPastWakeTimeOrRepeatsAfterRescheduling() {
        val first = FarmTimer("animalLove:a", "Chicken", 1000, null, "Apple",
            TimerCategory.ANIMAL_LOVE, expiresAt = 1300)
        val second = first.copy(key = "animalLove:b", readyAt = 1500, expiresAt = 3000)
        val timers = listOf(first, second)
        assertEquals(listOf(1100L, 1600L), planTimerNotifications(timers, 1000, 100) { _, _ -> false }.map { it.fireAt })
        assertEquals(listOf(second), planTimerNotifications(timers, 1000, 100, now = 1300) { _, _ -> false }.single().timers)
        assertEquals(listOf(second), planTimerNotifications(timers, 1000, -100) { key, stamp ->
            key == first.key && stamp == first.notificationStamp(100L)
        }.single().timers)
    }

    @Test
    fun cookingBuildingModeKeepsIndividualRowsInCacheWithoutAdditionalNotificationTimers() {
        val farm = JSONObject("""{"buildings":{"Bakery":[{"id":"a","crafting":[
            {"id":"1","name":"Cake","readyAt":3000},{"id":"2","name":"Bread","readyAt":4000}
        ]}]}}""")
        val timers = FarmTimers.extract(farm, com.sfl.browser.data.Prefs.COOKING_BUILDING, null, 2000) { null }
        val building = FarmTimers.fromJson(FarmTimers.toJson(timers)).single()
        assertEquals("Bakery", building.label)
        assertEquals(4000L, building.readyAt)
        assertEquals(listOf("Cake" to 3000L, "Bread" to 4000L),
            TimerUi.queueItems(listOf(building)).map { it.label to it.readyAt })
    }

    @Test
    fun craftingBoxAllDoneWaitsAcrossNamesAndGroupingWindowsButKeepsIndividualRows() {
        val farm = JSONObject("""{"craftingBox":{"queue":[
            {"id":"1","name":"Chair","readyAt":3000},
            {"id":"2","name":"Table","readyAt":9000},
            {"id":"3","name":"Chair","readyAt":6000}
        ]}}""")
        val items = FarmTimers.extract(farm, cookingMode = 0, farmId = null, now = 2000) { null }
        assertEquals(items, TimerUi.queueItems(items.filter(TimerUi::isQueueTimer)))
        assertEquals(listOf(3200L, 6200L, 9200L),
            planTimerNotifications(items, 500, 200) { _, _ -> false }.map { it.fireAt }.sorted())

        val group = planTimerNotifications(items, 500, 200, craftingBoxAllDone = true) { _, _ -> false }.single()
        assertEquals(9200L, group.fireAt)
        assertEquals(items, group.timers)
        assertEquals(listOf(FarmYield("Chair", 2.0), FarmYield("Table", 1.0)), group.yields)
    }

    @Test
    fun craftingBoxAllDoneDoesNotRepeatItemsPreviouslyNotified() {
        val first = FarmTimer("cbox:1", "Chair", 3000, null, "Crafting Box", TimerCategory.CRAFTING_BOX)
        val last = first.copy(key = "cbox:2", readyAt = 9000)
        val group = planTimerNotifications(listOf(first, last), 500, 0, craftingBoxAllDone = true) { key, _ ->
            key == first.key
        }.single()
        assertEquals(listOf(last), group.timers)
        assertEquals(9000L, group.fireAt)
        assertEquals(emptyList<TimerNotificationGroup>(),
            planTimerNotifications(listOf(first, last), 500, 0, craftingBoxAllDone = true) { _, _ -> true })
    }

    @Test
    fun fifteenStoneTimersWaitForOneNotificationWithTheirTotalYield() {
        val firstAt = 1_000_000L
        val stones = List(15) { i ->
            FarmTimer("stones:$i", "Stone", firstAt + i * 8_000, null, "", yieldAmount = 1.2)
        }
        val outside = stones.first().copy(key = "stones:later", readyAt = firstAt + 120_001)
        val groups = planTimerNotifications((stones + outside).reversed(), 120_000, 30_000) { _, _ -> false }
        assertEquals(2, groups.size)
        val group = groups.first()
        assertEquals(15, group.timers.size)
        assertEquals(firstAt + 142_000, group.fireAt)
        assertEquals(listOf(FarmYield("Stone", 18.0)), group.yields)
        assertEquals(emptyList<TimerNotificationGroup>(), groups.filter { it.fireAt <= group.fireAt - 1 })
        assertEquals(listOf(group), groups.filter { it.fireAt <= group.fireAt })
    }

    @Test
    fun timerNotificationPlanDoesNotResendFiredMembersOrInventIncompleteTotals() {
        val sent = FarmTimer("stones:0", "Stone", 100_000, null, "", yieldAmount = 2.0)
        val pending = sent.copy(key = "stones:1", readyAt = 110_000)
        val unknown = pending.copy(key = "stones:2", readyAt = 120_000, yieldAmount = null)
        val onlyGroup = planTimerNotifications(listOf(sent, pending, unknown), 120_000, 0) { key, at ->
            key == sent.key && at == sent.readyAt
        }.single()
        assertEquals(listOf(pending, unknown), onlyGroup.timers)
        assertNull(onlyGroup.yields)
    }

    @Test
    fun farmExtractionShowsPredictedCropsResourcesMachineAndAnimalProducts() {
        val farm = JSONObject("""{
            "bumpkin":{"skills":{}},
            "farmActivity":{},
            "inventory":{},
            "island":{"type":"basic"},
            "home":{"collectibles":{"Scarecrow":[{"coordinates":{"x":0,"y":0}}]}},
            "crops":{"1":{"x":2,"y":2,"crop":{"name":"Potato","plantedAt":1000,"baseDurationMs":2000}}},
            "trees":{"1":{"x":-5,"y":4,"wood":{"choppedAt":1000,"baseDurationMs":2000}}},
            "stones":{"1":{"x":-4,"y":4,"stone":{"minedAt":1000,"baseDurationMs":2000}}},
            "iron":{"1":{"x":-3,"y":4,"stone":{"minedAt":1000,"baseDurationMs":2000}}},
            "gold":{"1":{"x":-2,"y":4,"stone":{"minedAt":1000,"baseDurationMs":2000}}},
            "henHouse":{"animals":{"1":{"type":"Chicken","experience":240,"state":"ready","awakeAt":2000}}},
            "buildings":{"Crop Machine":[{"id":"machine","queue":[
                {"crop":"Potato","seeds":10,"readyAt":3000}
            ]}]}
        }""")
        val timers = FarmTimers.extract(farm, cookingMode = 0, farmId = 1, now = 4000) { null }
            .associateBy { it.key }
        assertEquals(1.2, timers.getValue("crop:1").yieldAmount)
        val machine = timers.getValue("cropmachine:machine:0")
        assertEquals(12.0, machine.yieldAmount)
        assertEquals(TimerCategory.CROP_MACHINE, machine.category)
        val chicken = timers.getValue("animal:henHouse:1")
        assertEquals(mapOf("Egg" to 1.0, "Feather" to 1.0), chicken.yieldProducts)
        assertEquals(TimerCategory.DISPLAY_ONLY, chicken.category)
        for (resource in listOf("tree", "stones", "iron", "gold")) {
            val timer = timers.getValue("$resource:1")
            assertEquals(3000L, timer.readyAt)
            assertNotNull(resource, timer.yieldAmount)
        }
    }

    @Test
    fun barleyHarvestPredictsThePlotYieldsFromFarmBoosts() {
        val farm = JSONObject("""{
            "bumpkin":{"skills":{"Old Farmer":1,"Acre Farm":1,"Hectare Farm":1,"Laurie's Gains":1}},
            "home":{"collectibles":{"Scarecrow":[{"coordinates":{"x":0,"y":0}}]}},
            "collectibles":{"Laurie the Chuckle Crow":[{"coordinates":{"x":0,"y":0}}]},
            "buds":{"a":{"type":"Snow","aura":"Green","coordinates":{"x":2,"y":2}},
                "b":{"stem":"3 Leaf Clover","aura":"Mythical"}}
        }""")
        val at = 2_000_000L
        val plots = (-3..3).flatMap { x -> (-7..-1).map { y -> x to y } } +
            (1..20).map { y -> 0 to y }
        val model = BarleyYield(YieldFarm(farm, CropGrowth(farm), at - 1))
        val timers = plots.mapIndexed { i, (x, y) ->
            val plot = JSONObject().put("x", x).put("y", y)
                .put("crop", JSONObject().put("name", "Barley").put("plantedAt", at - 1000))
            FarmTimer("crop:$i", "Barley", at, null, "", yieldAmount = model.amount(plot, at, null))
        }
        assertEquals(49, timers.count { it.yieldAmount == 2.46 })
        assertEquals(FarmYield("Barley", 163.74), FarmYield.total(timers))
    }

    @Test
    fun crimstoneLosesTheLastMineBonusWhenTheStreakHasExpired() {
        val farm = JSONObject("""{
            "bumpkin":{"skills":{"Fire Kissed":2},"equipped":{"tool":"Crimstone Hammer"}},
            "collectibles":{"Crim Peckster":[{"coordinates":{"x":0,"y":0}}]}
        }""")
        val node = JSONObject("""{"minesLeft":1}""")
        val stone = JSONObject("""{"minedAt":1000,"baseDurationMs":86400000}""")
        val growth = CropGrowth(farm)
        assertEquals(6.45, YieldFarm(farm, growth, 86_401_000).crimstone(node, stone))
        assertEquals(1.1, YieldFarm(farm, growth, 172_801_001).crimstone(node, stone))
    }

    @Test
    fun fullHiveIncludesWearableSkillAndCollectibleBoosts() {
        val farm = JSONObject("""{
            "bumpkin":{"skills":{"Sweet Bonus":2},"equipped":{"body":"Bee Suit"}},
            "collectibles":{"Ruins Flower":[{"coordinates":{"x":0,"y":0}}],
                "King of Bears":[{"coordinates":{"x":1,"y":1},"readyAt":2000}]}
        }""")
        assertEquals(1.30, YieldFarm(farm, CropGrowth(farm), 1000).fullHive())
        assertEquals(1.55, YieldFarm(farm, CropGrowth(farm), 2000).fullHive())
    }

    @Test
    fun saltYieldUsesOneChargeAndIncludesLegacySlotsAndChapterVipBonus() {
        val farm = JSONObject("""{
            "bumpkin":{"skills":{"Wide Rakes":2}},
            "inventory":{"Lifetime Farmer Banner":"1"}
        }""")
        val salt = JSONObject("""{"storedCharges":3,"harvesting":{"slots":[{}]}}""")
        val now = java.time.Instant.parse("2026-08-02T00:00:00Z").toEpochMilli()
        val calc = YieldFarm(farm, CropGrowth(farm), now)
        assertEquals(30.0, calc.saltPerHarvest(salt, now))
        assertEquals(26.0, calc.saltPerHarvest(salt, now + 86_400_000))
    }

    @Test
    fun groupedYieldAddsDecimalAmountsOnlyWhenEveryEntryHasTheSameKnownOutput() {
        val plot = FarmTimer("a", "Barley", 1000, null, "", yieldAmount = 2.16)
        val plots = List(69) { i ->
            plot.copy(key = i.toString(), yieldAmount = if (i < 49) 2.46 else 2.16)
        }
        assertEquals(FarmYield("Barley", 163.74), FarmYield.total(plots))
        assertEquals(null, FarmYield.total(plots + plot.copy(yieldAmount = null)))
        assertEquals(null, FarmYield.total(plots + plot.copy(yieldItem = "Wheat")))
    }

    @Test
    fun obsidianYieldCountsOnlyReadyPlacedBoosts() {
        val farm = JSONObject("""{
            "collectibles":{"Obsidian Turtle":[{"coordinates":{"x":0,"y":0}}],
                "Magma Stone":[{"coordinates":{"x":1,"y":0},"readyAt":2000}],
                "Macropinna":[{"readyAt":1000}]}
        }""")
        val growth = CropGrowth(farm)
        assertEquals(1.5, FarmYield.obsidian(growth, 1500), 0.00001)
        assertEquals(1.65, FarmYield.obsidian(growth, 2000), 0.00001)
    }

    @Test
    fun flowerYieldAppliesRecordedHitsAndExpiresTemporaryBonuses() {
        val farm = JSONObject("""{
            "collectibles":{"Humming Bird":[{"coordinates":{"x":0,"y":0}}],
                "Legendary Shrine":[{"createdAt":1000}]}
        }""")
        val growth = CropGrowth(farm)
        val skills = JSONObject("""{"Petalled Perk":1}""")
        val flower = JSONObject("""{"criticalHit":{"Humming Bird":1,"Butterfly":1,"Petalled Perk":1}}""")
        assertEquals(4.0, FarmYield.flower(flower, growth, skills, 2000))
        assertEquals(3.0, FarmYield.flower(flower, growth, skills, 86_401_000))
        assertEquals(2.5, FarmYield.flower(flower.put("amount", 2.5), growth, skills, 2000))
    }

    @Test
    fun cropMachineKeepsFinishedPacksAndAppliesBoostBeforeConsumingFuel() {
        val farm = JSONObject("""{"boostHistory":{"Tortoise Shrine":[{"from":10000,"to":50000}]}}""")
        val machine = JSONObject("""{
            "oilSettledAt":10000,"unallocatedOilTime":900,
            "queue":[
                {"crop":"Barley","readyAt":9000},
                {"crop":"Wheat","readyAt":99999,"baseDurationMs":1000},
                {"crop":"Rice","readyAt":99999,"baseDurationMs":100}
            ]
        }""")
        val timing = FarmActions.machineTiming(machine, CropGrowth(farm))
        assertEquals(
            listOf(0 to 9000L, 1 to 10900L),
            timing.packs.map { it.slot to it.readyAt },
        )
        assertEquals(false, timing.allPacksCanFinish)
        assertEquals(10900L, timing.oilRunsOutAt)
    }

    @Test
    fun cropMachineAllDoneOnlySchedulesAQueueThatCanFinishAndOilHasItsOwnTiming() {
        val farm = JSONObject("""{"buildings":{"Crop Machine":[
            {"id":"a","oilSettledAt":10000,"unallocatedOilTime":1000,"queue":[
                {"crop":"Wheat","baseDurationMs":500,"amount":2},
                {"crop":"Barley","baseDurationMs":1000,"amount":3}
            ]},
            {"id":"b","oilSettledAt":10000,"unallocatedOilTime":2000,"queue":[
                {"crop":"Wheat","baseDurationMs":500,"amount":2},
                {"crop":"Barley","baseDurationMs":1000,"amount":3}
            ]}
        ]}}""")
        val timers = FarmTimers.fromJson(FarmTimers.toJson(
            FarmTimers.extract(farm, cookingMode = 0, farmId = null, now = 10000) { null },
        ))
        val groups = planTimerNotifications(timers, windowMs = 100, offsetMs = 200, cropMachineAllDone = true) { _, _ -> false }
        val stalled = groups.single { it.timers.singleOrNull()?.isMachineOilAlert == true }
        assertEquals(11000L, stalled.fireAt)
        val complete = groups.single { it.cropMachineQueue != null }
        assertEquals(11700L, complete.fireAt)
        assertEquals(listOf("cropmachine:b:0", "cropmachine:b:1"), complete.timers.map { it.key })
        assertEquals(listOf(FarmYield("Wheat", 2.0), FarmYield("Barley", 3.0)), complete.yields)
    }

    @Test
    fun pausingCropMachineBeforeItsFuelRunsOutDoesNotAlertForOil() {
        val machine = JSONObject("""{
            "oilSettledAt":10000,"unallocatedOilTime":500,"removedAt":10200,"queue":[
                {"crop":"Wheat","baseDurationMs":1000},{"crop":"Rice","baseDurationMs":100}
            ]
        }""")
        val timing = FarmActions.machineTiming(machine, CropGrowth(JSONObject()))
        assertEquals(false, timing.allPacksCanFinish)
        assertNull(timing.oilRunsOutAt)
    }

    @Test
    fun readyToFeedAnimalsUseSeparateSpeciesCategoriesWithoutChangingCollection() {
        val farm = JSONObject("""{
            "henHouse":{"animals":{"1":{"type":"Chicken","state":"happy","awakeAt":2000}}},
            "barn":{"animals":{
                "2":{"type":"Cow","state":"idle","awakeAt":2100},
                "3":{"type":"Sheep","state":"happy","awakeAt":2200},
                "4":{"type":"Cow","state":"ready","awakeAt":2300}
            }},
            "pigpen":{"animals":{"5":{"type":"Pig","state":"sad","awakeAt":2400}}}
        }""")
        val timers = FarmTimers.extract(farm, 0, null, 1000) { null }
        assertEquals(listOf(
            "Feed Chicken" to TimerCategory.CHICKEN_FEEDING,
            "Feed Cow" to TimerCategory.COW_FEEDING,
            "Feed Sheep" to TimerCategory.SHEEP_FEEDING,
            "Collect from Cow" to TimerCategory.DISPLAY_ONLY,
            "Feed Pig" to TimerCategory.PIG_FEEDING,
        ), timers.map { it.label to it.category })
    }

    @Test
    fun honeyCountdownStopsIfTheAttachedFlowerExpiresTooSoon() {
        val hive = JSONObject("""{
            "honey":{"produced":86399000,"updatedAt":1000},
            "flowers":[{"attachedAt":1000,"attachedUntil":1350,"rate":2}]
        }""")
        assertEquals(0L, FarmActions.honeyReadyAt(hive, 1200))
        hive.getJSONArray("flowers").getJSONObject(0).put("attachedUntil", 1900)
        assertEquals(1500L, FarmActions.honeyReadyAt(hive, 1200))
        assertEquals(1600L, FarmActions.honeyReadyAt(hive, 1600))
    }

    @Test
    fun timerCachePreservesKnownYieldAndLeavesUnknownYieldUnset() {
        val timers = listOf(
            FarmTimer("a", "Compost", 1000, null, "", yieldAmount = 2.5),
            FarmTimer("b", "Barley", 1000, null, ""),
            FarmTimer("c", "Collect from Sheep", 1000, null, "",
                yieldProducts = mapOf("Wool" to 2.5, "Merino Wool" to 1.0)),
        )
        assertEquals(timers, FarmTimers.fromJson(FarmTimers.toJson(timers)))
    }

    @Test
    fun animalGroupsTotalEveryProductWithoutPresentingPartialYields() {
        val sheep = FarmTimer("a", "Collect from Sheep", 1000, null, "",
            yieldProducts = linkedMapOf("Wool" to 2.2, "Merino Wool" to 1.0))
        val other = sheep.copy(key = "b", yieldProducts = mapOf("Wool" to 1.1))
        assertEquals(
            listOf(FarmYield("Wool", 3.3), FarmYield("Merino Wool", 1.0)),
            FarmYield.totals(listOf(sheep, other)),
        )
        assertNull(FarmYield.totals(listOf(sheep, other.copy(yieldProducts = null))))
    }

    @Test
    fun machinePredictionsAdvancePastStoredPacksAndSumPerSeed() {
        val farm = JSONObject("""{"farmActivity":{"Sunflower Harvested":8}}""")
        val queue = MachineYields(farm, 1)
        val stored = JSONObject("""{"crop":"Sunflower","seeds":3,"amount":5.5}""")
        val later = JSONObject("""{"crop":"Sunflower","seeds":2}""")
        assertEquals(5.5, queue.amount(stored) { _, _ -> error("Stored pack was recalculated") })
        fun amount(roll: YieldRoll) = if (roll.hit("Green Amulet", 43.0)) 10.1 else 1.1
        val expected = (11..12).sumOf { amount(YieldRoll.forHarvest(1, "Sunflower", it)!!) }
        assertEquals(expected, queue.amount(later) { _, roll -> amount(checkNotNull(roll)) }!!, 0.00001)
        assertNull(MachineYields(farm, null).amount(later) { _, roll -> roll?.let(::amount) })
    }

    @Test
    fun oilYieldUsesThirdDrillBonusGearAndShrineExpiry() {
        val farm = JSONObject("""{
            "bumpkin":{"skills":{"Oil Extraction":2},"equipped":{"tool":"Oil Can"}},
            "farmHands":{"bumpkins":{"helper":{"equipped":{"shirt":"Oil Overalls"}}}},
            "collectibles":{"Stag Shrine":[{"createdAt":1000}]}
        }""")
        val growth = CropGrowth(farm)
        val reserve = JSONObject("""{"drilled":2}""")
        assertEquals(58.5, FarmActions.oilYield(farm, reserve, growth, 2000L))
        assertEquals(43.5, FarmActions.oilYield(farm, reserve, growth, 604_801_000L))
        reserve.put("drilled", 3)
        assertEquals(23.5, FarmActions.oilYield(farm, reserve, growth, 2000L))
    }
}
