package com.sfl.browser.notify

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.sfl.browser.R

/** Bounded, user-facing output for a manual check. Never includes request paths or response bodies. */
internal class PollFeedback(private val context: Context) {
    private val checks = linkedMapOf<Int, String>()
    private val priceErrors = linkedSetOf<String>()
    private var successCount = 0
    private var failureCount = 0
    private var priced = 0
    private var priceTotal = 0

    fun check(label: Int, result: SflApi.ApiResult, valid: Boolean = true) {
        record(label, apiError(result, valid))
    }

    fun check(label: Int, result: SflApi.ArrayResult) {
        record(label, when (result) {
            is SflApi.ArrayResult.Success -> null
            is SflApi.ArrayResult.HttpError -> context.getString(R.string.check_error_http, result.code)
            SflApi.ArrayResult.NoKey -> context.getString(R.string.check_error_no_key)
            SflApi.ArrayResult.Throttled -> context.getString(R.string.check_error_throttled)
            is SflApi.ArrayResult.Failure -> context.getString(R.string.check_error_request)
        })
    }

    fun unexpected(label: Int) = record(label, context.getString(R.string.check_error_unexpected))

    fun startPrices(total: Int) {
        priceTotal = total
    }

    fun price(result: SflApi.ApiResult, valid: Boolean = true) {
        val error = apiError(result, valid)
        if (error == null) {
            priced++
            successCount++
        } else {
            priceErrors += error
            failureCount++
        }
    }

    fun finishPrices() {
        checks[R.string.check_prices] = priceDetails()
    }

    private fun priceDetails(): String {
        if (priceTotal == 0) return context.getString(R.string.check_prices_no_alerts)
        val counts = context.getString(R.string.check_prices_counts, priced, priceTotal)
        return if (priceErrors.isEmpty()) counts else "$counts (${priceErrors.joinToString()})"
    }

    fun waiting(delay: Long) = workDataOf(
        WAIT_SECONDS to ((delay + 999L) / 1000L).toInt(),
        DETAILS to details(includeActivePrices = true),
    )

    private fun details(includeActivePrices: Boolean = false): String {
        val sections = checks.toMutableMap()
        if (includeActivePrices && priceTotal > 0) sections[R.string.check_prices] = priceDetails()
        return sections.entries.joinToString("\n") { (label, detail) ->
            context.getString(R.string.check_result_entry, context.getString(label), detail)
        }.ifEmpty { context.getString(R.string.check_nothing_ran) }
    }

    fun result(rateLimited: Boolean): ListenableWorker.Result {
        val outcome = when {
            successCount == 0 -> FAILURE
            failureCount > 0 -> PARTIAL
            else -> SUCCESS
        }
        val details = if (rateLimited) {
            context.getString(R.string.check_result_format, details(), context.getString(R.string.check_rate_limited_stop))
        } else details()
        val output = workDataOf(OUTCOME to outcome, DETAILS to details)
        return if (outcome == FAILURE) ListenableWorker.Result.failure(output)
        else ListenableWorker.Result.success(output)
    }

    private fun record(label: Int, error: String?) {
        checks[label] = error ?: context.getString(R.string.check_completed)
        if (error == null) successCount++ else failureCount++
    }

    private fun apiError(result: SflApi.ApiResult, valid: Boolean): String? = when (result) {
        is SflApi.ApiResult.Success -> if (valid) null else context.getString(R.string.check_error_response)
        is SflApi.ApiResult.HttpError -> context.getString(R.string.check_error_http, result.code)
        SflApi.ApiResult.NoKey -> context.getString(R.string.check_error_no_key)
        SflApi.ApiResult.Throttled -> context.getString(R.string.check_error_throttled)
        is SflApi.ApiResult.Failure -> context.getString(R.string.check_error_request)
    }

    companion object {
        const val OUTCOME = "outcome"
        const val DETAILS = "details"
        const val WAIT_SECONDS = "waitSeconds"
        const val SUCCESS = "success"
        const val PARTIAL = "partial"
        const val FAILURE = "failure"
        const val MANUAL = "manual"

        fun notStarted(context: Context, reason: Int): ListenableWorker.Result =
            ListenableWorker.Result.failure(
                workDataOf(
                    OUTCOME to FAILURE,
                    DETAILS to context.getString(
                        R.string.check_result_format,
                        context.getString(R.string.check_nothing_ran),
                        context.getString(reason),
                    ),
                ),
            )
    }
}
