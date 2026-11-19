package com.sfl.browser.overview

import android.content.Context
import com.sfl.browser.R
import com.sfl.browser.data.TimerCategory
import com.sfl.browser.notify.FarmDay
import com.sfl.browser.notify.FarmTimer

/**
 * The presentation rules the two timer screens share: which bucket a ready time
 * falls into, how a countdown reads, which section a timer belongs to, and
 * which pixel chip it wears.
 *
 * Ported from sfl-overview.com (`statusOf`, `formatRemaining`, `CATEGORY_ORDER`
 * and the game's Label colours) so the app and the site agree on what "soon"
 * means and on how a countdown is spelled.
 */
object TimerUi {

    /** Mirrors the site's `Status` — and its exact thresholds. */
    enum class Status { READY, SOON, LATER }

    /** `ready <= now` → ready; within the hour → soon; anything else → later. */
    fun statusOf(readyAt: Long, now: Long): Status = when {
        readyAt <= now -> Status.READY
        readyAt - now <= 60 * 60 * 1000L -> Status.SOON
        else -> Status.LATER
    }

    /**
     * Countdown text: `Ready` / `12s` / `6m 21s` / `1h 19m` / `3d 2h 5m`.
     * The largest two units are shown so a row never wraps.
     */
    fun formatRemaining(readyAt: Long, now: Long): String {
        var s = (readyAt - now).coerceAtLeast(0L) / 1000
        if (s <= 0) return "Ready"
        val d = s / 86_400; s %= 86_400
        val h = s / 3_600; s %= 3_600
        val m = s / 60; s %= 60
        return when {
            d > 0 -> "${d}d ${h}h ${m}m"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    /** A section: its display name and its slot in the site's section order. */
    data class Category(val title: String, val order: Int)

    fun isRaffleStart(timer: FarmTimer): Boolean =
        timer.key.startsWith("event:raffles:") && timer.key.endsWith(":start")

    fun dailyReset(label: String, now: Long) =
        FarmTimer("dailyReset", label, FarmDay.nextReset(now), null, "", TimerCategory.DISPLAY_ONLY)

    fun isQueueTimer(timer: FarmTimer): Boolean = when (timer.key.substringBefore(':')) {
        "craft", "building", "cbox" -> true
        else -> false
    }

    fun queueItems(timers: List<FarmTimer>): List<FarmTimer> =
        timers.flatMap { it.children.ifEmpty { listOf(it) } }.sortedBy { it.readyAt }

    // Fixed sections, ordered exactly like the site's CATEGORY_ORDER.
    private const val O_CROPS = 0
    private const val O_FRUIT = 1
    private const val O_GREENHOUSE = 2
    private const val O_FLOWERS = 3
    private const val O_RESOURCES = 4
    private const val O_LAVA = 5
    private const val O_TRAPS = 6
    private const val O_CAVE = 7
    private const val O_CRAFTING = 8
    private const val O_COOKING = 9
    private const val O_COLLECTIBLES = 10
    private const val O_EXPANSION = 11
    private const val O_OTHER = 12

    /**
     * Section a timer belongs to, derived from its key prefix.
     *
     * Cooking is special: the site groups by *building*, so a Bakery queue sits
     * in its own "Bakery" section rather than in one big "Cooking" pile. Those
     * dynamic sections all sort together at [O_COOKING], alphabetically within.
     */
    fun categoryOf(context: Context, timer: FarmTimer): Category {
        val prefix = timer.key.substringBefore(':')
        val fixed = when (prefix) {
            "crop" -> O_CROPS to context.getString(R.string.cat_crops)
            "fruit" -> O_FRUIT to context.getString(R.string.cat_fruit)
            "gh" -> O_GREENHOUSE to context.getString(R.string.cat_greenhouse)
            "flower" -> O_FLOWERS to context.getString(R.string.cat_flowers)
            "tree", "stones", "iron", "gold", "crimstones", "sunstones",
            "ascensionCrystals", "oil" -> O_RESOURCES to context.getString(R.string.cat_resources)
            "lava" -> O_LAVA to context.getString(R.string.cat_lava)
            "trap" -> O_TRAPS to context.getString(R.string.cat_traps)
            "cave" -> O_CAVE to context.getString(R.string.cat_cave)
            "cbox" -> O_CRAFTING to context.getString(R.string.cat_crafting)
            "collectible" -> O_COLLECTIBLES to context.getString(R.string.cat_collectibles)
            "expansion" -> O_EXPANSION to context.getString(R.string.cat_expansion)
            "cropmachine" -> O_OTHER to context.getString(R.string.cat_crop_machine)
            "composter" -> O_OTHER to context.getString(R.string.cat_composters)
            "quest" -> -1 to context.getString(R.string.cat_quests)
            "budbox" -> -1 to context.getString(R.string.cat_bud_box)
            "agingshed" -> O_OTHER to context.getString(R.string.cat_aging_shed)
            "petting" -> O_OTHER to context.getString(R.string.cat_petting)
            "animal" -> O_OTHER to context.getString(R.string.cat_animals)
            "beehive" -> O_OTHER to context.getString(R.string.cat_beehives)
            "salt" -> O_OTHER to context.getString(R.string.cat_salt)
            else -> null
        }
        if (fixed != null) return Category(fixed.second, fixed.first)

        // craft: → detail is the building; building: → label is the building.
        val building = if (prefix == "craft") timer.detail else timer.label
        val title = building.ifEmpty { context.getString(R.string.cat_cooking) }
        return Category(title, O_COOKING)
    }

    /** Chip fill for a status, e.g. green "Ready", amber "6m 12s". */
    fun chipBackground(status: Status): Int = when (status) {
        Status.READY -> R.drawable.sfl_chip_ready
        Status.SOON -> R.drawable.sfl_chip_soon
        Status.LATER -> R.drawable.sfl_chip_later
    }

    /** Text colour on that chip (amber takes ink, the others take white). */
    fun chipTextColor(status: Status): Int = when (status) {
        Status.READY -> R.color.sfl_on_chip_ready
        Status.SOON -> R.color.sfl_on_chip_soon
        Status.LATER -> R.color.sfl_on_chip_later
    }

    /** Neutral chip used for section headers and counts. */
    fun neutralChip(): Int = R.drawable.sfl_chip_default

    fun neutralTextColor(): Int = R.color.sfl_on_chip_default
}
