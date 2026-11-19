package com.sfl.browser.notify

import android.content.Context
import android.os.SystemClock
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.sfl.browser.R
import com.sfl.browser.data.ItemIcons
import com.sfl.browser.data.Prefs
import com.sfl.browser.data.TimelineEvents
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CancellationException

/**
 * One full check of the Community API: timers, raffles & auctions, own sales,
 * and price alerts — in that order.
 *
 * Everything here is **idempotent**: state maps (`scheduled`, `fired`,
 * `seenEvents`, `seenSales`) record what has already been handled, so running
 * twice in a row is harmless. The very first run (`baselineDone` false) records the
 * current state *silently* — otherwise the app would announce every crop that
 * was already ripe the moment it was installed.
 *
 * Requests are serialised inside [SflApi] at one per ~5.5s, which keeps a
 * five-endpoint poll inside the API's per-IP budget without extra pacing code.
 *
 * In chain mode (interval < 15 min) the worker re-enqueues itself before
 * returning; in periodic mode WorkManager does that for us.
 */
class PollWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    private val feedback = if (inputData.getBoolean(PollFeedback.MANUAL, false)) PollFeedback(applicationContext) else null
    private lateinit var retry: CommunityRateLimiter.Retry
    private var rateLimited = false

    override fun doWork(): Result {
        val ctx = applicationContext
        retry = CommunityRateLimiter.Retry(
            // WorkManager caps ordinary jobs at ten minutes. Leave room for the retry itself.
            deadline = SystemClock.elapsedRealtime() + 9 * 60_000L,
            isStopped = { isStopped },
            onWait = { delay ->
                if (!isStopped) feedback?.let {
                    setProgressAsync(if (delay == null) Data.EMPTY else it.waiting(delay))
                }
            },
        )

        if (!Prefs.hasApiKey(ctx)) {
            Notifier.postSystem(
                ctx, "no_key",
                ctx.getString(R.string.err_api_key_required_title),
                ctx.getString(R.string.err_api_key_required),
            )
            finish(ctx)
            return if (feedback == null) Result.success()
            else PollFeedback.notStarted(ctx, R.string.err_api_key_required)
        }

        val farmId = Prefs.farmId(ctx)
        if (farmId.isEmpty()) {
            Notifier.postSystem(
                ctx, "no_farm",
                ctx.getString(R.string.err_farm_id_title),
                ctx.getString(R.string.err_farm_id),
            )
            finish(ctx)
            return if (feedback == null) Result.success()
            else PollFeedback.notStarted(ctx, R.string.err_farm_id)
        }

        val baseline = !Prefs.baselineDone(ctx)
        val hardErrors = LinkedHashSet<String>()

        runSection(R.string.check_timers) { pollTimers(ctx, farmId, baseline, hardErrors) }

        if (!rateLimited && Prefs.enableEvents(ctx)) {
            runSection(R.string.check_events) { pollEvents(ctx, baseline, hardErrors) }
        }
        if (!rateLimited && Prefs.enableSales(ctx)) {
            runSection(R.string.check_sales) { pollSales(ctx, farmId, baseline, hardErrors) }
        }
        if (!rateLimited && Prefs.enablePrices(ctx)) {
            runSection(R.string.check_prices) {
                pollPrices(ctx, baseline, hardErrors)
                feedback?.finishPrices()
            }
        }

        if (baseline && !rateLimited) Prefs.setBaselineDone(ctx, true)
        reportErrors(ctx, hardErrors)
        finish(ctx)
        return feedback?.result(rateLimited) ?: Result.success()
    }

    private fun get(path: String) = SflApi.get(applicationContext, path, retry).also {
        if (it == SflApi.ApiResult.Throttled) rateLimited = true
    }

    private fun getData(path: String) = SflApi.getData(applicationContext, path, retry).also {
        if (it == SflApi.ApiResult.Throttled) rateLimited = true
    }

    private fun getDataArray(path: String) = SflApi.getDataArray(applicationContext, path, retry).also {
        if (it == SflApi.ArrayResult.Throttled) rateLimited = true
    }

    private inline fun runSection(label: Int, block: () -> Unit) {
        val report = feedback
        if (report == null) {
            block()
        } else {
            try {
                block()
            } catch (error: RuntimeException) {
                if (isStopped || error is CancellationException) throw error
                report.unexpected(label)
            }
        }
    }

    // ------------------------------------------------------------- timers

    private fun pollTimers(
        ctx: Context,
        farmId: String,
        baseline: Boolean,
        errors: MutableSet<String>,
    ) {
        when (val result = get("/community/farms/$farmId")) {
            is SflApi.ApiResult.Success -> {
                val farm = result.body.optJSONObject("farm")
                if (farm == null) {
                    feedback?.check(R.string.check_timers, result, valid = false)
                    errors.add("farm")
                    return
                }

                if (FarmTimerCache.refresh(ctx, farmId, result.body, farm, baseline) == null) {
                    feedback?.unexpected(R.string.check_timers)
                } else {
                    feedback?.check(R.string.check_timers, result)
                }
            }

            else -> {
                feedback?.check(R.string.check_timers, result)
                when (result) {
                    is SflApi.ApiResult.HttpError -> errors.add("http_${result.code}")
                    is SflApi.ApiResult.NoKey -> errors.add("no_key")
                    else -> Unit // network blip or throttle — next poll will retry
                }
            }
        }
    }

    // ------------------------------------------------------- raffles/auctions

    private fun pollEvents(ctx: Context, baseline: Boolean, errors: MutableSet<String>) {
        val lead = Prefs.leadMillis(ctx)
        val liveKeys = HashSet<String>()

        when (val raffles = getDataArray("/community/data?type=raffles").also {
            feedback?.check(R.string.check_raffles, it)
        }) {
            is SflApi.ArrayResult.Success -> {
                val now = System.currentTimeMillis()
                val seen = Prefs.seenEvents(ctx)
                TimelineEvents.store(ctx, "raffles", raffles.array)
                for (i in 0 until raffles.array.length()) {
                    val raffle = raffles.array.optJSONObject(i) ?: continue
                    val id = raffle.str("id")
                    if (id.isEmpty()) continue
                    handleStart(
                        ctx = ctx, kind = EventAlarm.KIND_RAFFLE, id = id,
                        startAt = raffle.optLong("startAt"), lead = lead, now = now,
                        title = ctx.getString(R.string.notif_raffle_title),
                        text = prizeSummary(raffle.optJSONObject("prizes"))
                            .ifEmpty { ctx.getString(R.string.notif_check_game) },
                        seen = seen, liveKeys = liveKeys, baseline = baseline,
                    )
                }
                pruneEvents(seen, liveKeys, EventAlarm.KIND_RAFFLE)
                Prefs.setSeenEvents(ctx, seen)
            }

            is SflApi.ArrayResult.HttpError -> errors.add("raffles_${raffles.code}")
            is SflApi.ArrayResult.NoKey -> errors.add("no_key")
            else -> Unit
        }
        if (rateLimited) return

        // `type=auctions` returns `data: { auctions: [...], totalSupply }` — an
        // object, so this goes through the object reader rather than the array one.
        when (val auctions = getData("/community/data?type=auctions").also {
            feedback?.check(
                R.string.check_auctions, it,
                valid = it is SflApi.ApiResult.Success && it.body.optJSONArray("auctions") != null,
            )
        }) {
            is SflApi.ApiResult.Success -> {
                val inner = auctions.body.optJSONArray("auctions")
                if (inner == null) {
                    errors.add("auctions")
                    return
                }
                val now = System.currentTimeMillis()
                val seen = Prefs.seenEvents(ctx)
                TimelineEvents.store(ctx, "auctions", inner)

                for (i in 0 until inner.length()) {
                    val auction = inner.optJSONObject(i) ?: continue
                    val id = auction.str("auctionId").ifEmpty { auction.str("id") }
                    if (id.isEmpty()) continue
                    handleStart(
                        ctx = ctx, kind = EventAlarm.KIND_AUCTION, id = id,
                        startAt = auction.optLong("startAt"), lead = lead, now = now,
                        title = ctx.getString(R.string.notif_auction_title),
                        text = ctx.getString(
                            R.string.notif_auction_text,
                            auctionLabel(auction),
                            auction.optDouble("sfl", 0.0),
                        ),
                        seen = seen, liveKeys = liveKeys, baseline = baseline,
                    )
                }
                pruneEvents(seen, liveKeys, EventAlarm.KIND_AUCTION)
                Prefs.setSeenEvents(ctx, seen)
            }

            is SflApi.ApiResult.HttpError -> errors.add("auctions_${auctions.code}")
            is SflApi.ApiResult.NoKey -> errors.add("no_key")
            else -> Unit
        }

    }

    /**
     * Post (or arm) the "starts soon" reminder for one event.
     *
     * Three windows: before the lead time the alarm carries it, inside the lead
     * window the poll posts it (covering battery-saving mode and a missed
     * alarm), and once past the start there is nothing left to say.
     */
    private fun handleStart(
        ctx: Context,
        kind: String,
        id: String,
        startAt: Long,
        lead: Long,
        now: Long,
        title: String,
        text: String,
        seen: JSONObject,
        liveKeys: MutableSet<String>,
        baseline: Boolean,
    ) {
        if (startAt <= 0) return
        val key = EventAlarm.reminderKey(kind, id)
        liveKeys += key
        val reminderAt = startAt - lead

        val withinWindow = now >= reminderAt && now < startAt
        if (withinWindow && !seen.has(key)) {
            if (!baseline) Notifier.postEvent(ctx, key, title, text)
            seen.put(key, now)
        }

        // Arms when the reminder is still ahead, disarms once it has passed.
        EventAlarm.update(ctx, kind, id, title, text, reminderAt)
    }

    // -------------------------------------------------------------- sales

    private fun pollSales(
        ctx: Context,
        farmId: String,
        baseline: Boolean,
        errors: MutableSet<String>,
    ) {
        val path = "/community/data?type=marketplaceProfile&farmId=$farmId"
        when (val profile = getData(path)) {
            is SflApi.ApiResult.Success -> {
                val trades = profile.body.optJSONArray("trades")
                if (trades == null) {
                    feedback?.check(R.string.check_sales, profile, valid = false)
                    return
                }
                val seen = Prefs.seenSales(ctx)
                val liveKeys = HashSet<String>()

                for (i in 0 until trades.length()) {
                    val trade = trades.optJSONObject(i) ?: continue
                    val id = trade.str("id")
                    if (id.isEmpty()) continue
                    liveKeys += id

                    if (seen.has(id)) continue
                    if (baseline) {
                        seen.put(id, trade.optLong("fulfilledAt"))
                        continue
                    }
                    // Only trades *we* initiated count as our sale.
                    val initiator = trade.optJSONObject("initiatedBy")?.opt("id")?.toString() ?: ""
                    if (initiator != farmId) {
                        seen.put(id, 0)
                        continue
                    }

                    val quantity = trade.optInt("quantity", 1)
                    val item = ItemIcons.nameForId(
                        ctx, trade.str("collection"), trade.optInt("itemId", -1),
                    ) ?: ctx.getString(R.string.notif_sale_item, trade.optInt("itemId", -1))
                    val sfl = trade.optDouble("sfl", 0.0)
                    val buyer = trade.optJSONObject("fulfilledBy")?.str("username").orEmpty()

                    Notifier.postSale(
                        ctx, id,
                        ctx.getString(R.string.notif_sale_title, quantity, item),
                        if (buyer.isEmpty()) {
                            ctx.getString(R.string.notif_sale_text, sfl)
                        } else {
                            ctx.getString(R.string.notif_sale_text_from, sfl, buyer)
                        },
                    )
                    seen.put(id, trade.optLong("fulfilledAt"))
                }

                prune(seen, liveKeys)
                Prefs.setSeenSales(ctx, seen)
                feedback?.check(R.string.check_sales, profile)
            }

            else -> {
                feedback?.check(R.string.check_sales, profile)
                when (profile) {
                    is SflApi.ApiResult.HttpError -> errors.add("profile_${profile.code}")
                    is SflApi.ApiResult.NoKey -> errors.add("no_key")
                    else -> Unit
                }
            }
        }
    }

    // ------------------------------------------------------- price alerts

    private fun pollPrices(ctx: Context, baseline: Boolean, errors: MutableSet<String>) {
        val alerts = Prefs.alerts(ctx)
        if (alerts.isEmpty()) return
        feedback?.startPrices(alerts.size)
        val batched = Prefs.batchedPriceChecks(ctx)
        val quotes = PriceQuoteBatch(batched, { LocalDate.now(ZoneOffset.UTC) }, ::getData)
        for (alert in alerts) {
            if (isStopped) throw CancellationException("Price check cancelled")
            val (result, floor, valid) = quotes.get(alert.collection, alert.id)
            feedback?.price(result, valid)
            if (rateLimited) break
            when (result) {
                is SflApi.ApiResult.HttpError -> {
                    val endpoint = if (batched) "marketplaceActivity" else "tradeable"
                    errors.add("${endpoint}_${result.code}")
                }
                SflApi.ApiResult.NoKey -> errors.add("no_key")
                else -> Unit
            }
            if (floor == null) continue
            val crossed = if (alert.below) floor <= alert.threshold else floor >= alert.threshold
            if (alert.armed && crossed) {
                if (Prefs.updatePriceAlertArming(ctx, alert, armed = false) && !baseline) {
                    Notifier.postPrice(
                        ctx, "${alert.collection}:${alert.id}",
                        ctx.getString(R.string.notif_price_title, alert.name),
                        ctx.getString(
                            R.string.notif_price_text,
                            floor,
                            if (alert.below) "<=" else ">=",
                            alert.threshold,
                        ),
                    )
                }
            } else if (!alert.armed && !crossed) {
                Prefs.updatePriceAlertArming(ctx, alert, armed = true)
            }
        }
    }

    // ------------------------------------------------------------ plumbing

    private fun reportErrors(ctx: Context, errors: Set<String>) {
        for (kind in errors) {
            val (title, text) = when {
                kind == "no_key" -> ctx.getString(R.string.err_api_key_required_title) to
                    ctx.getString(R.string.err_api_key_required)

                kind.startsWith("http_") || kind.contains("_401") || kind.contains("_403") ->
                    ctx.getString(R.string.err_api_rejected_title) to
                        ctx.getString(R.string.err_api_rejected, kind)

                else -> ctx.getString(R.string.err_api_failed_title) to
                    ctx.getString(R.string.err_api_failed, kind)
            }
            Notifier.postSystem(ctx, "poll_$kind", title, text)
        }
    }

    private fun finish(ctx: Context) {
        if (!isStopped && !PollScheduler.isPeriodic(ctx)) {
            PollScheduler.enqueueNext(ctx, afterCurrent = inputData.getBoolean(PollScheduler.CHAINED, false))
        }
    }

    private fun pruneEvents(seen: JSONObject, keep: Set<String>, kind: String) {
        val prefix = "$kind:"
        val expired = seen.keys().asSequence().filter { it.startsWith(prefix) && it !in keep }.toList()
        expired.forEach { seen.remove(it) }
    }

    private fun prune(map: JSONObject, keep: Set<String>) {
        val iterator = map.keys()
        val drop = ArrayList<String>()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key !in keep) drop += key
        }
        drop.forEach { map.remove(it) }
    }

    /** `optString` but strict: only real JSON strings count, not `toString()`. */
    private fun JSONObject.str(key: String): String {
        val value = opt(key)
        return if (value is String) value else ""
    }

    private fun prizeSummary(prizes: JSONObject?): String {
        if (prizes == null) return ""
        val names = LinkedHashSet<String>()
        val keys = prizes.keys()
        while (keys.hasNext()) {
            val prize = prizes.optJSONObject(keys.next()) ?: continue
            val items = prize.optJSONObject("items") ?: continue
            val itemKeys = items.keys()
            while (itemKeys.hasNext()) names += itemKeys.next()
            if (names.size >= 3) break
        }
        return names.take(3).joinToString(", ")
    }

    private fun auctionLabel(auction: JSONObject): String {
        val wearable = auction.opt("wearable")
        val name = when (wearable) {
            is String -> wearable
            is JSONObject -> wearable.str("name").ifEmpty { wearable.str("id") }
            else -> ""
        }
        return name.ifEmpty { auction.str("type").ifEmpty { "Auction item" } }
    }

    /** "in 10m" / "in 2h" / "now" for a future epoch-ms timestamp. */
    @Suppress("unused")
    private fun relative(whenMs: Long): String {
        val delta = whenMs - System.currentTimeMillis()
        return when {
            delta <= 0 -> "now"
            delta < 60_000 -> "in ${delta / 1_000}s"
            delta < 3_600_000 -> "in ${delta / 60_000}m"
            delta < 86_400_000 -> "in ${delta / 3_600_000}h"
            else -> "in ${delta / 86_400_000}d"
        }
    }
}
