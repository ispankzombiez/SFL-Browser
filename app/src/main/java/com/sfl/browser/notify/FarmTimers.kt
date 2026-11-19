package com.sfl.browser.notify

import android.content.Context
import com.sfl.browser.data.ItemIcons
import com.sfl.browser.data.Prefs
import com.sfl.browser.data.TimerCategory
import org.json.JSONArray
import org.json.JSONObject

/**
 * One ready-time the poller found on the farm.
 *
 * @param key stable identity — survives across polls so `scheduled` / `fired`
 *   can recognise "the same timer" and not re-notify it
 * @param label item name, e.g. `Kale`, `Antipasto`, `Fire Pit`
 * @param readyAt epoch ms when the thing finishes
 * @param icon bundled asset path for [label], or null when we ship no art
 * @param detail where it is, e.g. `Crop plot 4`, `Bakery`, `Crafting Box`
 */
data class FarmTimer(
    val key: String,
    val label: String,
    val readyAt: Long,
    val icon: String?,
    val detail: String,
    val category: TimerCategory = TimerCategory.OTHER,
    val yieldAmount: Double? = null,
    val yieldItem: String? = null,
    val yieldProducts: Map<String, Double>? = null,
    val expiresAt: Long? = null,
    val children: List<FarmTimer> = emptyList(),
    val machineQueueCanFinish: Boolean = false,
)

/**
 * Extracts farm timers and actionable entries from a `/community/farms/{id}` payload.
 * Predictions use that snapshot only; missing inputs leave the yield unset.
 *
 * The farm is a big heterogeneous bag of subsystems, each with its own shape,
 * so this is a list of small readers rather than one clever generic walk. Each
 * follows the same contract:
 *
 *  - the clock starts at the subsystem's anchor (`plantedAt`, `choppedAt`,
 *    `minedAt`, `drilledAt`, `harvestedAt`, …) and runs for `baseDurationMs`;
 *    field and greenhouse crops also accrue growth faster during boost windows;
 *  - entries with no `baseDurationMs` are **legacy** — the game back-dates their
 *    start by an unknown boost amount and we cannot recover a reliable finish
 *    time, so they are skipped rather than guessed at;
 *  - a completed timer stays visible while its entry is present in the farm;
 *    rows with no timing anchor are skipped. Display-only actions do not
 *    participate in the notification schedule.
 *
 * Other readers using the duration model still use the unadjusted base duration.
 *
 * Consumers pass a ready-made `readyAt` for subsystems that carry no duration
 * at all (queues, traps, cooldowns) — those readers are marked `// readyAt`.
 */
object FarmTimers {

    internal fun extractSnapshot(
        context: Context, farm: JSONObject, cookingMode: Int, farmId: Long?, now: Long,
    ): Snapshot {
        val app = context.applicationContext
        return extractSnapshot(farm, cookingMode, farmId, now) { ItemIcons.pathForName(app, it) }
    }

    internal data class Snapshot(val timers: List<FarmTimer>, val refreshAt: Long)

    internal fun extract(
        farm: JSONObject,
        cookingMode: Int,
        farmId: Long?,
        now: Long,
        iconFor: (String) -> String?,
    ): List<FarmTimer> = extractSnapshot(farm, cookingMode, farmId, now, iconFor).timers

    internal fun extractSnapshot(
        farm: JSONObject,
        cookingMode: Int,
        farmId: Long?,
        now: Long,
        iconFor: (String) -> String?,
    ): Snapshot {
        val out = ArrayList<FarmTimer>(64)
        val sink = Sink(iconFor, out)

        val clock = FarmTimerClock(now)
        val growth = CropGrowth(farm, clock)
        val yields = YieldFarm(farm, growth, now)
        crops(farm, sink, growth, yields, farmId)
        fruitPatches(farm, sink, yields, farmId)
        val resources = resourcePredictions(farm, farmId, growth, yields)
        trees(farm, sink, resources["trees"].orEmpty())
        finiteResources(farm, sink, yields, resources)
        oilReserves(farm, sink, growth, now)
        buildings(farm, sink, cookingMode)
        craftingBox(farm, sink)
        greenhouse(farm, sink, growth, yields, farmId)
        flowers(farm, sink, growth, now)
        crabTraps(farm, sink)
        lavaPits(farm, sink, growth, now)
        expansionConstruction(farm, sink)
        collectibles(farm, sink)
        cave(farm, sink)
        val machineCrops = FieldCropYieldCalculator(yields)
        out += FarmActions.extract(farm, growth, yields, farmId, iconFor) { crop, at, roll ->
            machineCrops.predict(JSONObject().put("name", crop), harvestAt = at, roll = roll)
        }

        return Snapshot(out.sortedBy { it.readyAt }, clock.refreshAt)
    }

    /** Resolves the bundled icon once, at the point we know the label. */
    private class Sink(val iconFor: (String) -> String?, val out: MutableList<FarmTimer>) {
        fun add(
            key: String,
            label: String,
            readyAt: Long,
            detail: String,
            category: TimerCategory,
            yieldAmount: Double? = null,
            yieldItem: String? = null,
            children: List<FarmTimer> = emptyList(),
        ) {
            if (readyAt <= 0) return
            val name = label.trim().ifEmpty { detail.trim().ifEmpty { "Timer" } }
            out += FarmTimer(
                key = key,
                label = name,
                readyAt = readyAt,
                icon = iconFor(name),
                detail = detail,
                category = category,
                yieldAmount = yieldAmount,
                yieldItem = yieldItem,
                children = children,
            )
        }

        /** `anchor + duration` form; drops legacy entries with no duration. */
        fun addTimed(
            key: String,
            anchor: Long,
            duration: Long,
            label: String,
            detail: String,
            category: TimerCategory,
            yieldAmount: Double? = null,
            yieldItem: String? = null,
        ) {
            if (anchor <= 0 || duration <= 0) return
            add(key, label, anchor + duration, detail, category, yieldAmount, yieldItem)
        }
    }

    // ------------------------------------------------------------- land plots

    private fun crops(farm: JSONObject, sink: Sink, growth: CropGrowth, yields: YieldFarm, farmId: Long?) {
        val plots = farm.optJSONObject("crops") ?: return
        data class Plot(val id: String, val state: JSONObject, val crop: JSONObject, val at: Long)
        val calculator = FieldCropYieldCalculator(yields)
        val rolls = HarvestRolls(farm, farmId)
        val entries = plots.keys().asSequence().mapNotNull { id ->
            val plot = plots.optJSONObject(id) ?: return@mapNotNull null
            val crop = plot.optJSONObject("crop") ?: return@mapNotNull null
            val at = growth.plotReadyAt(crop, plot)
            if (at <= 0) return@mapNotNull null
            Plot(id, plot, crop, at)
        }.sortedBy { it.at }
        for ((id, plot, crop, at) in entries) {
            sink.add(
                key = "crop:$id", readyAt = at,
                label = crop.optString("name"), detail = "Crop plot $id",
                category = TimerCategory.CROPS,
                yieldAmount = FarmYield.stored(crop) ?: calculator.predict(
                    crop, plot, at, rolls.next(crop.optString("name")),
                ),
            )
        }
    }

    private data class PlantEntry(val id: String, val state: JSONObject, val plant: JSONObject, val at: Long)

    private fun fruitPatches(farm: JSONObject, sink: Sink, yields: YieldFarm, farmId: Long?) {
        val patches = farm.optJSONObject("fruitPatches") ?: return
        val entries = patches.keys().asSequence().mapNotNull { id ->
            val patch = patches.optJSONObject(id) ?: return@mapNotNull null
            val fruit = patch.optJSONObject("fruit") ?: return@mapNotNull null
            // harvestsLeft == 0 means the tree is dead until replanted.
            if (fruit.optInt("harvestsLeft", 1) <= 0) return@mapNotNull null
            // Regrowing fruit counts from the last harvest, fresh fruit from planting.
            val anchor = fruit.optLong("harvestedAt").takeIf { it > 0 } ?: fruit.optLong("plantedAt")
            val duration = fruit.optLong("baseDurationMs")
            if (anchor <= 0 || duration <= 0) return@mapNotNull null
            PlantEntry(id, patch, fruit, anchor + duration)
        }.sortedBy { it.at }
        val rolls = HarvestRolls(farm, farmId)
        for ((id, patch, fruit, at) in entries) {
            val name = fruit.optString("name")
            sink.add(
                key = "fruit:$id", readyAt = at,
                label = name, detail = "Fruit patch $id",
                category = TimerCategory.FRUIT,
                yieldAmount = FarmYield.stored(fruit) ?: PlantYields.fruit(
                    fruit, patch.optJSONObject("fertiliser")?.optString("name"), yields,
                    rolls.next(name), maxOf(yields.now, at),
                ),
            )
        }
    }

    private fun resourcePredictions(
        farm: JSONObject, farmId: Long?, growth: CropGrowth, yields: YieldFarm,
    ): Map<String, Map<String, Double?>> {
        val placed = buildList {
            for (group in listOf("trees", "stones", "iron", "gold")) {
                val nodes = farm.optJSONObject(group) ?: continue
                val tree = group == "trees"
                for (id in nodes.keys()) {
                    val node = nodes.optJSONObject(id) ?: continue
                    if (node.isNull("x") || node.isNull("y") || node.optInt("minesLeft", 1) <= 0) continue
                    val resource = node.optJSONObject(if (tree) "wood" else "stone") ?: continue
                    val anchor = resource.optLong(if (tree) "choppedAt" else "minedAt")
                    // Prediction order includes legacy nodes even when they have no countdown row.
                    val at = if (!resource.isNull("baseDurationMs")) {
                        growth.resourcePredictionReadyAt(group, anchor, resource.optLong("baseDurationMs"))
                    } else {
                        ResourceYields.legacyReadyAt(group, anchor)
                    }
                    if (at > 0) add(ResourceYields.Node(group, id, node, at))
                }
            }
        }
        return ResourceYields.predict(yields, farmId, placed)
    }

    private fun trees(farm: JSONObject, sink: Sink, predicted: Map<String, Double?>) {
        val trees = farm.optJSONObject("trees") ?: return
        for (id in trees.keys()) {
            val wood = trees.optJSONObject(id)?.optJSONObject("wood") ?: continue
            sink.addTimed(
                key = "tree:$id", anchor = wood.optLong("choppedAt"),
                duration = wood.optLong("baseDurationMs"),
                label = wood.optString("name").ifEmpty { "Wood" },
                detail = "Tree $id",
                category = TimerCategory.TREES,
                yieldAmount = FarmYield.stored(wood) ?: predicted[id],
            )
        }
    }

    /**
     * Stones, iron, gold, crimstones, sunstones and ascension crystals all share
     * `{ minesLeft, stone: { minedAt, baseDurationMs? } }`.
     */
    private fun finiteResources(
        farm: JSONObject, sink: Sink, yields: YieldFarm, predicted: Map<String, Map<String, Double?>>,
    ) {
        for (group in arrayOf("stones", "iron", "gold", "crimstones", "sunstones", "ascensionCrystals")) {
            val nodes = farm.optJSONObject(group) ?: continue
            for (id in nodes.keys()) {
                val node = nodes.optJSONObject(id) ?: continue
                if (node.optInt("minesLeft", 1) <= 0) continue
                val stone = node.optJSONObject("stone") ?: continue
                sink.addTimed(
                    key = "$group:$id", anchor = stone.optLong("minedAt"),
                    duration = stone.optLong("baseDurationMs"),
                    label = mineralName(group), detail = mineralName(group),
                    category = TimerCategory.RESOURCES,
                    yieldAmount = when (group) {
                        "sunstones" -> 1.0
                        "ascensionCrystals" -> 3.0
                        "crimstones" -> FarmYield.stored(stone) ?: yields.crimstone(node, stone)
                        else -> FarmYield.stored(stone) ?: predicted[group]?.get(id)
                    },
                    yieldItem = if (group == "ascensionCrystals") "Ascension Shard" else null,
                )
            }
        }
    }

    private fun oilReserves(farm: JSONObject, sink: Sink, growth: CropGrowth, now: Long) {
        val reserves = farm.optJSONObject("oilReserves") ?: return
        for (id in reserves.keys()) {
            val reserve = reserves.optJSONObject(id) ?: continue
            val oil = reserve.optJSONObject("oil") ?: continue
            val drilledAt = oil.optLong("drilledAt")
            val duration = oil.optLong("baseDurationMs")
            sink.addTimed(
                key = "oil:$id", anchor = drilledAt,
                duration = duration,
                label = "Oil", detail = "Oil reserve $id",
                category = TimerCategory.OIL,
                yieldAmount = FarmActions.oilYield(farm, reserve, growth, maxOf(now, drilledAt + duration)),
            )
        }
    }

    // ------------------------------------------------------------ cooking etc.

    /**
     * Buildings hold a `crafting[]` queue of `{ id, name, readyAt }`.
     *
     * In per-item mode each craft becomes its own timer; in building mode they
     * collapse into one timer firing when the whole queue drains.
     */
    private fun buildings(farm: JSONObject, sink: Sink, cookingMode: Int) {
        val buildings = farm.optJSONObject("buildings") ?: return
        for (name in buildings.keys()) {
            val instances = buildings.optJSONArray(name) ?: continue
            for (i in 0 until instances.length()) {
                val instance = instances.optJSONObject(i) ?: continue
                val instanceId = instance.opt("id")?.toString() ?: i.toString()
                val crafting = instance.optJSONArray("crafting") ?: continue
                if (crafting.length() == 0) continue

                val entries = (0 until crafting.length()).mapNotNull { j ->
                    val item = crafting.optJSONObject(j) ?: return@mapNotNull null
                    val at = item.optLong("readyAt")
                    if (at <= 0) return@mapNotNull null
                    val label = item.optString("name").trim().ifEmpty { name }
                    FarmTimer("craft:$name:$instanceId:${item.optString("id", j.toString())}",
                        label, at, sink.iconFor(label), name, TimerCategory.BUILDINGS)
                }
                if (cookingMode == Prefs.COOKING_BUILDING) {
                    sink.add(
                        key = "building:$name:$instanceId", label = name,
                        readyAt = entries.maxOfOrNull { it.readyAt } ?: 0, detail = "${crafting.length()} items",
                        category = TimerCategory.BUILDINGS, children = entries,
                    )
                } else {
                    sink.out += entries
                }
            }
        }
    }

    /** Crafting Box: a flat `queue[]` of `{ id, name, readyAt }`. */
    private fun craftingBox(farm: JSONObject, sink: Sink) {
        val queue = farm.optJSONObject("craftingBox")?.optJSONArray("queue") ?: return
        for (i in 0 until queue.length()) {
            val item = queue.optJSONObject(i) ?: continue
            sink.add(
                key = "cbox:${item.optString("id", i.toString())}",
                label = item.optString("name"),
                readyAt = item.optLong("readyAt"),
                detail = "Crafting Box",
                category = TimerCategory.CRAFTING_BOX,
                yieldAmount = 1.0,
            )
        }
    }

    /** Greenhouse pots: `pots.{n}.plant.{ plantedAt, baseDurationMs, name }`. */
    private fun greenhouse(farm: JSONObject, sink: Sink, growth: CropGrowth, yields: YieldFarm, farmId: Long?) {
        val pots = farm.optJSONObject("greenhouse")?.optJSONObject("pots") ?: return
        val entries = pots.keys().asSequence().mapNotNull { id ->
            val pot = pots.optJSONObject(id) ?: return@mapNotNull null
            val plant = pot.optJSONObject("plant") ?: return@mapNotNull null
            val at = growth.greenhouseReadyAt(plant, pot)
            if (at <= 0) return@mapNotNull null
            PlantEntry(id, pot, plant, at)
        }.sortedBy { it.at }
        val rolls = HarvestRolls(farm, farmId)
        for ((id, pot, plant, at) in entries) {
            val name = plant.optString("name")
            sink.add(
                key = "gh:$id", readyAt = at,
                label = name, detail = "Greenhouse pot $id",
                category = TimerCategory.GREENHOUSE,
                yieldAmount = FarmYield.stored(plant) ?: PlantYields.greenhouse(
                    plant, pot.optJSONObject("fertiliser")?.optString("name"), yields,
                    rolls.next(name), maxOf(yields.now, at),
                ),
            )
        }
    }

    /** Flower beds: `flowerBeds.{n}.flower.{ plantedAt, baseDurationMs, name }`. */
    private fun flowers(farm: JSONObject, sink: Sink, growth: CropGrowth, now: Long) {
        val beds = farm.optJSONObject("flowers")?.optJSONObject("flowerBeds") ?: return
        val skills = farm.optJSONObject("bumpkin")?.optJSONObject("skills")
        for (id in beds.keys()) {
            val bed = beds.optJSONObject(id) ?: continue
            val flower = bed.optJSONObject("flower") ?: continue
            val startedAt = flower.optLong("plantedAt")
            val duration = flower.optLong("baseDurationMs")
            sink.addTimed(
                key = "flower:$id", anchor = startedAt, duration = duration,
                label = flower.optString("name"), detail = "Flower bed $id",
                category = TimerCategory.FLOWERS,
                yieldAmount = if (!bed.isNull("x") || !bed.isNull("y")) {
                    FarmYield.flower(flower, growth, skills, maxOf(now, startedAt + duration))
                } else null,
            )
        }
    }

    /** Crab traps: `trapSpots.{n}.waterTrap.{ readyAt, type }` — no duration field. */
    private fun crabTraps(farm: JSONObject, sink: Sink) {
        val spots = farm.optJSONObject("crabTraps")?.optJSONObject("trapSpots") ?: return
        for (id in spots.keys()) {
            val trap = spots.optJSONObject(id)?.optJSONObject("waterTrap") ?: continue
            sink.add(
                key = "trap:$id",
                label = trap.optString("type").ifEmpty { "Crab Trap" },
                readyAt = trap.optLong("readyAt"),
                detail = "Crab trap $id",
                category = TimerCategory.CRAB_TRAPS,
            )
        }
    }

    /** Lava pits: `collectedAt > 0` means it has already been harvested. */
    private fun lavaPits(farm: JSONObject, sink: Sink, growth: CropGrowth, now: Long) {
        val pits = farm.optJSONObject("lavaPits") ?: return
        for (id in pits.keys()) {
            val pit = pits.optJSONObject(id) ?: continue
            if (pit.optLong("collectedAt") > 0) continue
            sink.add(
                key = "lava:$id", label = "Lava Pit",
                readyAt = pit.optLong("readyAt"), detail = "Lava pit $id",
                category = TimerCategory.LAVA_PITS,
                yieldAmount = if (!pit.isNull("x") || !pit.isNull("y")) {
                    FarmYield.obsidian(growth, maxOf(now, pit.optLong("readyAt")))
                } else null,
                yieldItem = "Obsidian",
            )
        }
    }

    private fun expansionConstruction(farm: JSONObject, sink: Sink) {
        val expansion = farm.optJSONObject("expansionConstruction") ?: return
        if (expansion.has("readyAt")) {
            sink.add(
                key = "expansion", label = "Expansion",
                readyAt = expansion.optLong("readyAt"), detail = "Land expansion",
                category = TimerCategory.EXPANSION,
            )
            return
        }
        // Older payloads expose a list of in-progress expansions.
        val list = expansion.optJSONArray("expansions") ?: return
        var latestAt = 0L
        for (i in 0 until list.length()) {
            latestAt = maxOf(latestAt, list.optJSONObject(i)?.optLong("readyAt") ?: 0L)
        }
        sink.add(
            key = "expansion", label = "Expansion",
            readyAt = latestAt, detail = "Land expansion",
            category = TimerCategory.EXPANSION,
        )
    }

    /**
     * Collectibles with a cooldown (a shrine recharging, a machine resting) live
     * in `collectibles.{name}[]` with `{ createdAt, readyAt }`.
     */
    private fun collectibles(farm: JSONObject, sink: Sink) {
        val collectibles = farm.optJSONObject("collectibles") ?: return
        for (name in collectibles.keys()) {
            val list = collectibles.optJSONArray(name) ?: continue
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val readyAt = item.optLong("readyAt")
                val createdAt = item.optLong("createdAt")
                if (createdAt <= 0 || readyAt <= createdAt) continue
                sink.add(
                    key = "collectible:$name:$i", label = name,
                    readyAt = readyAt, detail = name,
                    category = TimerCategory.COLLECTIBLES,
                )
            }
        }
    }

    /** Cave machines: `machines.{n}.batch.{ readyAt, recipe }`. */
    private fun cave(farm: JSONObject, sink: Sink) {
        val machines = farm.optJSONObject("cave")?.optJSONObject("machines") ?: return
        for (id in machines.keys()) {
            val batch = machines.optJSONObject(id)?.optJSONObject("batch") ?: continue
            sink.add(
                key = "cave:$id",
                label = batch.optString("recipe").ifEmpty { "Cave Machine" },
                readyAt = batch.optLong("readyAt"),
                detail = "Cave machine $id",
                category = TimerCategory.CAVE,
            )
        }
    }

    private fun mineralName(group: String): String = when (group) {
        "stones" -> "Stone"
        "iron" -> "Iron"
        "gold" -> "Gold"
        "crimstones" -> "Crimstone"
        "sunstones" -> "Sunstone"
        "ascensionCrystals" -> "Ascension Crystal"
        else -> group
    }

    // --------------------------------------------------------- serialisation

    /**
     * The poller hands its findings to the alarm receiver (and to the next
     * poll) through SharedPreferences, so timers round-trip as JSON. Keys stay
     * identical across polls — that is what makes dedupe work.
     */
    fun toJson(timers: List<FarmTimer>): JSONArray {
        val array = JSONArray()
        timers.forEach { t ->
            array.put(
                JSONObject()
                    .put("key", t.key)
                    .put("label", t.label)
                    .put("readyAt", t.readyAt)
                    .put("icon", t.icon ?: JSONObject.NULL)
                    .put("detail", t.detail)
                    .put("category", t.category.key)
                    .put("yieldAmount", t.yieldAmount)
                    .put("yieldItem", t.yieldItem)
                    .put("yieldProducts", t.yieldProducts?.let { JSONObject(it) })
                    .put("expiresAt", t.expiresAt)
                    .put("children", t.children.takeIf { it.isNotEmpty() }?.let(::toJson))
                    .put("machineQueueCanFinish", true.takeIf { t.machineQueueCanFinish })
            )
        }
        return array
    }

    fun fromJson(array: JSONArray): List<FarmTimer> = buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val readyAt = o.optLong("readyAt")
            if (readyAt <= 0) continue
            val label = o.optString("label")
            if (label.isEmpty()) continue
            add(
                FarmTimer(
                    key = o.optString("key").ifEmpty { "$label:$readyAt" },
                    label = label,
                    readyAt = readyAt,
                    icon = if (o.isNull("icon")) null else o.optString("icon").ifEmpty { null },
                    detail = o.optString("detail"),
                    category = TimerCategory.fromKey(o.optString("category"))
                        ?: TimerCategory.fromTimerKey(o.optString("key")),
                    yieldAmount = o.optDouble("yieldAmount").takeIf { it.isFinite() && it >= 0 },
                    yieldItem = o.optString("yieldItem").takeIf { it.isNotBlank() && it != "null" },
                    yieldProducts = o.optJSONObject("yieldProducts")?.let(FarmYield::readProducts),
                    expiresAt = o.optLong("expiresAt").takeIf { it > 0 },
                    children = o.optJSONArray("children")?.let(::fromJson).orEmpty(),
                    machineQueueCanFinish = o.optBoolean("machineQueueCanFinish"),
                )
            )
        }
    }
}
