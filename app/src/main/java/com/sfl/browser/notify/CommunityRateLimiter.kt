package com.sfl.browser.notify

import java.util.concurrent.CancellationException

internal class CommunityRateLimiter(
    private val now: () -> Long,
    private val sleep: (Long) -> Unit,
) {
    class Retry(
        val deadline: Long,
        val isStopped: () -> Boolean,
        /** The full cooldown remaining (ms), or null once the wait ends. */
        val onWait: (Long?) -> Unit,
    )

    data class Response(val result: SflApi.ApiResult, val cooldown: Long = 0)

    private val lock = Any()
    private var nextRequestAt = 0L
    private var cooldownUntil: Long? = null

    fun execute(
        retry: Retry?,
        restoreCooldown: () -> Long,
        saveCooldown: (Long) -> Unit,
        request: () -> Response,
    ): SflApi.ApiResult {
        var wasRetried = false
        var showingWait = false
        try {
            while (true) {
                var rateWait: Long
                var pacingWait: Long
                val response = synchronized(lock) {
                    if (retry?.isStopped?.invoke() == true || Thread.currentThread().isInterrupted) {
                        throw CancellationException("Community API request cancelled")
                    }
                    val start = now()
                    val cooldown = cooldownUntil ?: (start + restoreCooldown()).also { cooldownUntil = it }
                    rateWait = cooldown - start
                    pacingWait = nextRequestAt - start
                    if (rateWait > 0 || pacingWait > 0) null
                    else {
                        if (showingWait) {
                            showingWait = false
                            retry?.onWait?.invoke(null)
                        }
                        nextRequestAt = start + SflApi.MIN_GAP_MS
                        request().also {
                            if (it.cooldown > 0) {
                                cooldownUntil = now() + it.cooldown
                                saveCooldown(it.cooldown)
                            }
                        }
                    }
                }
                if (response != null) {
                    if (response.result != SflApi.ApiResult.Throttled || retry == null || wasRetried) {
                        return response.result
                    }
                    wasRetried = true
                    continue
                }
                if (rateWait > 0) {
                    // Screen refreshes can keep showing the cache. Only poll jobs wait and resume.
                    if (retry == null || rateWait >= retry.deadline - now()) return SflApi.ApiResult.Throttled
                    if (!showingWait) {
                        showingWait = true
                        retry.onWait(rateWait)
                    }
                }
                val wait = maxOf(rateWait, pacingWait)
                // Do not hold the request lock while sleeping; workers also check for cancellation.
                sleep(if (retry == null) wait else minOf(wait, 250L))
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Community API request interrupted")
        } finally {
            if (showingWait) retry?.onWait?.invoke(null)
        }
    }
}
