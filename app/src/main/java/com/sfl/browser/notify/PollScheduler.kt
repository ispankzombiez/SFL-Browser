package com.sfl.browser.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.sfl.browser.data.Prefs
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Keeps [PollWorker] running.
 *
 * Two strategies, chosen by the configured interval:
 *
 *  - **>= 15 minutes** — a real `PeriodicWorkRequest`, which WorkManager repeats
 *    on its own schedule and survives app force-stops reasonably well.
 *  - **< 15 minutes** — Android rejects periods shorter than 15 minutes, so the
 *    worker re-enqueues itself as a delayed one-shot at the end of every run.
 *    Same effect, at the cost of needing one run to complete before the next is
 *    queued (a crash therefore costs one interval, which the boot receiver and
 *    the settings screen both repair by re-scheduling).
 *
 * Either way the interval is clamped to [Prefs.MIN_INTERVAL_SECONDS] because
 * the API only allows about one request per five seconds per IP.
 */
object PollScheduler {

    private const val PERIODIC_WORK = "sfl_poll_periodic"
    private const val CHAIN_WORK = "sfl_poll_next"
    private const val RUN_NOW_WORK = "sfl_poll_now"
    internal const val CHAINED = "chained"

    /** WorkManager's own floor for a periodic request. */
    private const val PERIODIC_MIN_MS = 15 * 60 * 1000L

    fun isPeriodic(context: Context): Boolean =
        Prefs.intervalSeconds(context) * 1000L >= PERIODIC_MIN_MS

    /** (Re)arm polling. Call from settings, boot, and app start. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val workManager = WorkManager.getInstance(app)
        val intervalMs = Prefs.intervalSeconds(app) * 1000L

        if (intervalMs >= PERIODIC_MIN_MS) {
            // Drop any one-shot chain so the two strategies never overlap.
            workManager.cancelUniqueWork(CHAIN_WORK)
            val request = PeriodicWorkRequestBuilder<PollWorker>(intervalMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints(app))
                .build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request,
            )
        } else {
            workManager.cancelUniqueWork(PERIODIC_WORK)
            enqueueNext(app, replace = true)
        }
    }

    /**
     * Queue the next chained run. Called at the end of every worker execution
     * in chain mode; also used to restart the chain after boot.
     */
    fun enqueueNext(context: Context, replace: Boolean = false, afterCurrent: Boolean = false) {
        val app = context.applicationContext
        val request = OneTimeWorkRequestBuilder<PollWorker>()
            .setInputData(workDataOf(CHAINED to true))
            .setInitialDelay(Prefs.intervalSeconds(app), TimeUnit.SECONDS)
            .setConstraints(constraints(app))
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(
            CHAIN_WORK,
            when {
                replace -> ExistingWorkPolicy.REPLACE
                afterCurrent -> ExistingWorkPolicy.APPEND_OR_REPLACE
                else -> ExistingWorkPolicy.KEEP
            },
            request,
        )
    }

    /**
     * Run one check right now, under its own unique name so it never
     * disturbs the regular schedule (periodic or chained).
     */
    data class ManualCheck(val id: UUID, val enqueue: Operation)

    fun runNow(context: Context): ManualCheck {
        val app = context.applicationContext
        val request = OneTimeWorkRequestBuilder<PollWorker>()
            .setInputData(workDataOf(PollFeedback.MANUAL to true))
            .build()
        val enqueue = WorkManager.getInstance(app).enqueueUniqueWork(
            RUN_NOW_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return ManualCheck(request.id, enqueue)
    }

    /** A settings-triggered refresh must not replace a manual check the user is watching. */
    fun refreshNow(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            RUN_NOW_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PollWorker>().build(),
        )
    }

    /** Stop polling entirely (used when the API key is removed). */
    fun cancel(context: Context) {
        val app = context.applicationContext
        WorkManager.getInstance(app).cancelUniqueWork(PERIODIC_WORK)
        WorkManager.getInstance(app).cancelUniqueWork(CHAIN_WORK)
    }

    private fun constraints(context: Context): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(
                if (Prefs.wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()
}
