package com.sfl.browser.data

import android.content.Context
import com.sfl.browser.notify.FarmTimer
import com.sfl.browser.notify.FarmTimerCache
import com.sfl.browser.notify.FarmTimers
import com.sfl.browser.notify.SflApi
import java.util.concurrent.Executors

/**
 * Feeds the Overview and Timeline screens.
 *
 * Both screens want the same thing — the farm's current timers, refreshed when
 * they can be — so the fetching lives here rather than in either activity.
 *
 * Two rules keep us inside the API's courtesy limits:
 *
 *  1. The last poll's result is the cache, so a screen paints instantly from
 *     [Prefs.timersJson] instead of blocking on the network.
 *  2. A refresh is skipped when [Prefs.lastFetchedAt] is fresher than
 *     [SflApi.MIN_GAP_MS] (and `SflApi` would sleep anyway), so opening both
 *     screens in quick succession costs at most one request.
 */
object TimerFeed {

    /** Why a refresh did not produce anything newer. */
    enum class Failure { NO_KEY, NO_FARM, BUSY, NETWORK, HTTP }

    /** What a caller gets back: always the best data we have, plus why not newer. */
    data class Result(
        val timers: List<FarmTimer>,
        val fetchedAt: Long,
        /** True if we refreshed successfully, including when the API confirms the cache is current. */
        val refreshed: Boolean,
        val failure: Failure? = null,
        val message: String? = null,
        val eventsRefreshed: Boolean? = null,
    )

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "timer-feed").apply { isDaemon = true }
    }

    /** The poller's cache — never hits the network. */
    fun cached(context: Context): Result = Result(
        timers = FarmTimers.fromJson(Prefs.timersJson(context)),
        fetchedAt = Prefs.lastFetchedAt(context),
        refreshed = false,
    )

    /**
     * Serve the cache immediately on [onResult], then refresh on a background
     * thread and deliver a second, fresher result. [onDone] runs once the
     * attempt has concluded either way — including when the throttle says
     * "another fetch just happened, don't bother" — so callers can stop their
     * spinner without having to guess.
     */
    fun load(
        context: Context,
        includeEvents: Boolean = false,
        onResult: (Result) -> Unit,
        onDone: () -> Unit = {},
    ) {
        val app = context.applicationContext
        fun Result.withEvents(result: Boolean? = null): Result = if (includeEvents) {
            copy(timers = timers + TimelineEvents.cached(app), eventsRefreshed = result)
        } else this
        onResult(cached(app).withEvents())

        executor.execute {
            try {
                val refreshed = fetch(app)
                if (refreshed != null) onResult(refreshed.withEvents())
                if (includeEvents && Prefs.hasApiKey(app)) {
                    val complete = TimelineEvents.refresh(app)
                    onResult((refreshed ?: cached(app)).withEvents(complete))
                }
            } finally {
                onDone()
            }
        }
    }

    /** Runs on [executor]; returns null when nothing changed worth reporting. */
    private fun fetch(context: Context): Result? {
        val farmId = Prefs.farmId(context).trim()
        if (farmId.isEmpty()) {
            return cached(context).copy(refreshed = false, failure = Failure.NO_FARM)
        }
        if (!Prefs.hasApiKey(context)) {
            return cached(context).copy(failure = Failure.NO_KEY)
        }

        // Another screen (or the poll worker) may have just fetched; don't
        // burn a request we'd be throttled on anyway.
        val since = System.currentTimeMillis() - Prefs.lastFetchedAt(context)
        if (since in 1 until SflApi.MIN_GAP_MS) {
            return null
        }

        return when (val result = SflApi.get(context, "/community/farms/$farmId")) {
            is SflApi.ApiResult.Success -> {
                val farm = result.body.optJSONObject("farm")
                if (farm == null) {
                    cached(context).copy(failure = Failure.HTTP, message = "no farm payload")
                } else {
                    val refresh = FarmTimerCache.refresh(context, farmId, result.body, farm) ?: return null
                    Result(refresh.timers, refresh.fetchedAt, refreshed = true)
                }
            }

            SflApi.ApiResult.Throttled ->
                cached(context).copy(failure = Failure.BUSY)

            SflApi.ApiResult.NoKey ->
                cached(context).copy(failure = Failure.NO_KEY)

            is SflApi.ApiResult.HttpError ->
                cached(context).copy(failure = Failure.HTTP, message = "HTTP ${result.code}")

            is SflApi.ApiResult.Failure ->
                cached(context).copy(failure = Failure.NETWORK, message = result.message)
        }
    }
}
