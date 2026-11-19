package com.sfl.browser.notify

import com.sfl.browser.data.TimerCategory
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

internal object FarmActions {
    private const val PET_COOLDOWN = 7_200_000L
    private val budTypes = listOf("Plaza", "Woodlands", "Cave", "Sea", "Castle",
        "Port", "Retreat", "Saphiro", "Snow", "Beach")
    private val composters = listOf("Compost Bin", "Turbo Composter", "Premium Composter")

    fun extract(
        farm: JSONObject,
        growth: CropGrowth,
        yields: YieldFarm,
        farmId: Long?,
        iconFor: (String) -> String?,
        cropSeedYield: (String, Long, YieldRoll?) -> Double?,
    ): List<FarmTimer> = buildList {
        val now = yields.now
        fun entry(
            prefix: String, id: String, label: String, at: Long,
            amount: Double? = null, products: Map<String, Double>? = null, iconName: String = label,
            expiresAt: Long? = null,
        ) {
            if (label.isBlank() || at <= 0) return
            add(FarmTimer(
                "$prefix:$id", label, at, iconFor(iconName), "",
                TimerCategory.DISPLAY_ONLY, amount?.takeIf { it.isFinite() && it >= 0 },
                yieldProducts = products, expiresAt = expiresAt,
            ))
        }

        val buildings = farm.optJSONObject("buildings")
        val machineYields = MachineYields(farm, farmId)
        val machines = objects(buildings?.optJSONArray("Crop Machine")).map { (i, machine) ->
            machine.optString("id", i.toString()) to machineTiming(machine, growth)
        }.toList()
        val packs = machines.flatMap { (id, timing) ->
            timing.packs.map { Triple(id, it, timing.allPacksCanFinish) }
        }.sortedBy { it.second.readyAt }
        for ((id, packEntry, canFinish) in packs) {
            val (slot, pack, at) = packEntry
            val crop = pack.optString("crop")
            if (crop.isBlank()) continue
            val amount = machineYields.amount(pack) { crop, roll ->
                cropSeedYield(crop, maxOf(now, at), roll)
            }
            add(FarmTimer(
                "cropmachine:$id:$slot", crop, at, iconFor(crop), "",
                TimerCategory.CROP_MACHINE, amount, machineQueueCanFinish = canFinish,
            ))
        }
        for ((id, timing) in machines) {
            val outAt = timing.oilRunsOutAt ?: continue
            add(FarmTimer("machineOil:$id", "Crop Machine", outAt, iconFor("Crop Machine"), "",
                TimerCategory.CROP_MACHINE))
        }

        val workshop = WorkshopYields(yields, farmId)
        for (name in composters) {
            for ((i, building) in objects(buildings?.optJSONArray(name))
                .sortedBy { it.second.optJSONObject("producing")?.optLong("readyAt") }) {
                val batch = building.optJSONObject("producing") ?: continue
                val output = batch.optJSONObject("items") ?: continue
                val id = building.optString("id", "$name:$i")
                val readyAt = batch.optLong("readyAt")
                if (readyAt <= 0) continue
                val products = workshop.compost(name, output)
                entry("composter", id, products?.keys?.firstOrNull() ?: name, readyAt, products = products)
            }
        }

        val todayStart = FarmDay.start(now)
        val reset = FarmDay.nextReset(now)
        val quest = farm.optJSONObject("telegram")?.optJSONObject("quest")
        if (quest != null) {
            yields.checkAgainAt(reset, quest.optLong("startAt"))
            // A dated quest record can remain after the choices are gone.
            val choices = quest.optJSONArray("choices")
            if (quest.optLong("startAt") in todayStart..now &&
                choices != null && (0 until choices.length()).any { !choices.isNull(it) }
            ) {
                entry("quest", "telegram", "Telegram daily quest", todayStart, expiresAt = reset)
            }
        }

        val buds = farm.optJSONObject("buds")
        if (buds != null && buds.length() > 0) {
            yields.checkAgainAt(reset)
            val today = budTypes[((FarmDay.index(now) + 2) % budTypes.size).toInt()]
            val opened = farm.optJSONObject("pumpkinPlaza")?.optJSONObject("budBox")
                ?.optLong("openedAt") ?: 0L
            val ownsTodaysBud = buds.keys().asSequence().any {
                buds.optJSONObject(it)?.optString("type") == today
            }
            if (ownsTodaysBud && opened < todayStart) {
                entry("budbox", "daily", "Bud Box", todayStart, expiresAt = reset)
            }
        }

        val racks = farm.optJSONObject("agingShed")?.optJSONObject("racks")
        if (objects(buildings?.optJSONArray("Aging Shed")).any {
                it.second.optJSONObject("coordinates") != null
            }) {
            for ((rack, label) in mapOf(
                "aging" to "Aging Rack", "fermentation" to "Fermentation Rack", "spice" to "Spice Rack",
            )) {
                for ((i, job) in objects(racks?.optJSONArray(rack))) {
                    val readyAt = job.optLong("readyAt")
                    if (readyAt <= 0) continue
                    val recipe = if (rack == "spice") job.optString("recipe").ifBlank { null } else null
                    entry("agingshed", "$rack:${job.optString("id", i.toString())}",
                        recipe ?: label, readyAt, amount = recipe?.let { workshop.spice(it, job) })
                }
            }
        }

        val pets = farm.optJSONObject("pets")
        for (kind in listOf("common", "nfts")) {
            val byId = pets?.optJSONObject(kind) ?: continue
            for (id in byId.keys()) {
                val pet = byId.optJSONObject(id) ?: continue
                if (!pet.has("pettedAt")) continue
                val placed = if (kind == "common") growth.isBuilt(pet.optString("name"), now, includePetHouse = true)
                    else pet.optJSONObject("coordinates") != null
                if (placed) entry("petting", "$kind:$id", "Pet", pet.optLong("pettedAt") + PET_COOLDOWN)
            }
        }

        for (key in listOf("henHouse", "barn", "pigpen")) {
            val animals = farm.optJSONObject(key)?.optJSONObject("animals") ?: continue
            for (id in animals.keys()) {
                val animal = animals.optJSONObject(id) ?: continue
                val state = animal.optString("state")
                if (state == "sick") continue
                val type = animal.optString("type").takeIf { it.isNotBlank() } ?: continue
                val label = if (state == "ready") "Collect from $type" else "Feed $type"
                val wakesAt = growth.animalReadyAt(animal)
                if (wakesAt > 0) {
                    val category = if (state == "ready") TimerCategory.DISPLAY_ONLY
                        else TimerCategory.feedingFor(type) ?: TimerCategory.DISPLAY_ONLY
                    add(FarmTimer("animal:$key:$id", label, wakesAt, iconFor(type), "", category,
                        yieldProducts = AnimalYields.predict(animal, yields)))
                }
                val golden = when (type) {
                    "Chicken" -> "Gold Egg"
                    "Cow" -> "Golden Cow"
                    "Sheep" -> "Golden Sheep"
                    else -> null
                }
                if (golden != null && yields.built(golden)) continue
                val asleepAt = animal.optLong("asleepAt")
                if (wakesAt > asleepAt) yields.checkAgainAt(asleepAt)
                val love = AnimalLove.next(animal, wakesAt, now) ?: continue
                yields.checkAgainAt(wakesAt)
                add(FarmTimer(
                    "animalLove:$key:$id:$asleepAt:${love.slot}",
                    type, love.opensAt, iconFor(type), love.item, TimerCategory.ANIMAL_LOVE,
                    expiresAt = wakesAt,
                ))
            }
        }

        val hives = farm.optJSONObject("beehives")
        hives?.keys()?.forEach { id ->
            val hive = hives.optJSONObject(id) ?: return@forEach
            if (hive.isNull("x") && hive.isNull("y")) return@forEach
            entry("beehive", id, "Honey", honeyReadyAt(hive, now), yields.fullHive())
        }

        val nodes = farm.optJSONObject("saltFarm")?.optJSONObject("nodes")
        nodes?.keys()?.forEach { id ->
            val salt = nodes.optJSONObject(id)?.optJSONObject("salt") ?: return@forEach
            val next = salt.optLong("nextChargeAt")
            if (salt.optInt("storedCharges") <= 0) yields.checkAgainAt(next)
            val charged = salt.optInt("storedCharges") > 0 || (next in 1..now)
            val at = if (charged) salt.optLong("claimedAt").takeIf { it in 1..now } ?: now else next
            entry("salt", id, "Salt", at, yields.saltPerHarvest(salt, at))
        }
    }

    internal data class Pack(val slot: Int, val data: JSONObject, val readyAt: Long)

    internal data class MachineTiming(val packs: List<Pack>, val allPacksCanFinish: Boolean, val oilRunsOutAt: Long?)

    internal fun machineTiming(machine: JSONObject, growth: CropGrowth): MachineTiming {
        val packs = mutableListOf<Pack>()
        var allCanFinish = true
        var oilOut: Long? = null
        var cursor = machine.optLong("oilSettledAt")
        var fuel = machine.optLong("unallocatedOilTime")
        val pause = machine.optLong("removedAt", Long.MAX_VALUE)
        for ((i, pack) in objects(machine.optJSONArray("queue"))) {
            val at = if (!pack.has("baseDurationMs") || pack.isNull("baseDurationMs") || cursor <= 0) {
                val stop = pack.optLong("growsUntil")
                if (cursor <= 0 && stop > 0 && pack.optLong("readyAt") <= 0 &&
                    pack.optLong("growTimeRemaining") > 0 && fuel <= 0 && stop < pause
                ) oilOut = stop
                pack.optLong("readyAt")
            } else if (fuel > 0 && cursor < pause) {
                val work = pack.optLong("baseDurationMs")
                val finish = if (work == 0L) cursor else growth.machinePackReadyAt(cursor, work)
                if (finish >= cursor && finish <= pause && finish - cursor <= fuel) {
                    fuel -= finish - cursor
                    cursor = finish
                    finish
                } else {
                    if (finish - cursor > fuel && cursor + fuel < pause) {
                        oilOut = cursor + fuel
                    } else {
                        cursor = pause
                    }
                    fuel = 0
                    0L
                }
            } else {
                if (fuel <= 0 && cursor < pause && pack.optLong("baseDurationMs") > 0) {
                    oilOut = oilOut ?: cursor
                }
                0L
            }
            if (at > 0) packs.add(Pack(i, pack, at)) else allCanFinish = false
        }
        return MachineTiming(packs, allCanFinish, oilOut)
    }

    internal fun honeyReadyAt(hive: JSONObject, now: Long): Long {
        val honey = hive.optJSONObject("honey") ?: return 0L
        val updatedAt = honey.optLong("updatedAt")
        var produced = honey.optDouble("produced", 0.0)
        val flowers = objects(hive.optJSONArray("flowers")).map { it.second }.toList()
        for (flower in flowers) {
            val from = maxOf(updatedAt, flower.optLong("attachedAt"))
            val until = minOf(now, flower.optLong("attachedUntil"))
            produced += (until - from).coerceAtLeast(0) * flower.optDouble("rate", 1.0)
        }
        if (produced >= FarmDay.DAY_MS) return now
        val boundaries = flowers.flatMap {
            listOf(it.optLong("attachedAt"), it.optLong("attachedUntil"))
        }.filter { it > now }.distinct().sorted()
        var from = now
        for (end in boundaries) {
            val rate = flowers.sumOf {
                if (it.optLong("attachedAt") <= from && from < it.optLong("attachedUntil")) {
                    it.optDouble("rate", 1.0)
                } else 0.0
            }
            val work = (end - from) * rate
            val remaining = FarmDay.DAY_MS - produced
            if (rate > 0 && work >= remaining) return from + ceil(remaining / rate).toLong()
            produced += work
            from = end
        }
        return 0L
    }

    internal fun oilYield(farm: JSONObject, reserve: JSONObject, growth: CropGrowth, now: Long): Double? {
        val drilled = reserve.optLong("drilled", -1)
        val bumpkin = farm.optJSONObject("bumpkin") ?: return null
        if (drilled < 0) return null
        fun gear(player: JSONObject?): Sequence<String> {
            val items = player?.optJSONObject("equipped") ?: return emptySequence()
            return items.keys().asSequence().map { items.optString(it) }
        }
        val hands = farm.optJSONObject("farmHands")?.optJSONObject("bumpkins")
        val wearables = (gear(bumpkin) + (hands?.keys()?.asSequence()?.flatMap {
            gear(hands.optJSONObject(it))
        } ?: emptySequence())).toSet()
        val rank = (bumpkin.optJSONObject("skills")?.optInt("Oil Extraction") ?: 0).coerceIn(0, 3)
        val bonusDrill = drilled % 3 == 2L
        var amount = 10.0 + listOf(0.0, 1.0, 1.5, 2.0)[rank]
        if (bonusDrill) amount += if (growth.hasActiveStagShrine(now)) 35.0 else 20.0
        if (growth.isBuilt("Battle Fish", now)) amount += 0.05
        if (growth.isBuilt("Ladybug Cow", now)) amount += 1.0
        if (growth.isBuilt("Knight Chicken", now)) amount += 0.1
        if ("Oil Can" in wearables) amount += 2.0
        if ("Oil Overalls" in wearables) amount += 10.0
        if ("Oil Gallon" in wearables) amount += 5.0
        return kotlin.math.round(amount * 100) / 100
    }

    private fun objects(array: JSONArray?): Sequence<Pair<Int, JSONObject>> =
        (0 until (array?.length() ?: 0)).asSequence().mapNotNull { i ->
            array?.optJSONObject(i)?.let { i to it }
        }
}
