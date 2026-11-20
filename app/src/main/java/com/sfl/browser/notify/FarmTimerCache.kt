package com.sfl.browser.notify

import android.content.Context
import android.util.Log
import com.sfl.browser.data.Prefs
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException

internal data class FarmTimerRevision(
    val farmId: String,
    val cookingMode: Int,
    val updatedAt: String,
    val evaluatedAt: Long,
    val refreshAt: Long,
) {
    fun canReuse(farmId: String, cookingMode: Int, updatedAt: String?, now: Long): Boolean =
        this.farmId == farmId && this.cookingMode == cookingMode &&
            this.updatedAt == updatedAt && now in evaluatedAt until refreshAt

    fun toJson(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("farmId", farmId).put("cookingMode", cookingMode).put("updatedAt", updatedAt)
        .put("evaluatedAt", evaluatedAt).put("refreshAt", refreshAt)

    companion object {
        private const val SCHEMA = 3

        fun fromJson(json: JSONObject): FarmTimerRevision? {
            if (json.optInt("schema") != SCHEMA) return null
            val saved = updatedAt(json) ?: return null
            return FarmTimerRevision(
                json.optString("farmId"), json.optInt("cookingMode", -1), saved,
                json.optLong("evaluatedAt", Long.MAX_VALUE), json.optLong("refreshAt", 0L),
            )
        }

        fun updatedAt(response: JSONObject): String? {
            val value = response.opt("updatedAt") as? String ?: return null
            return try {
                Instant.parse(value).toString()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}

/** Reuse the derived timers, never the API response. Both farm fetchers share this cache. */
internal object FarmTimerCache {
    data class Refresh(val timers: List<FarmTimer>, val fetchedAt: Long)

    @Synchronized
    fun refresh(
        context: Context,
        farmId: String,
        response: JSONObject,
        farm: JSONObject,
        baseline: Boolean? = null,
    ): Refresh? {
        val now = System.currentTimeMillis()
        val mode = Prefs.cookingMode(context)
        val updatedAt = FarmTimerRevision.updatedAt(response)
        if (updatedAt == null && response.has("updatedAt")) {
            Log.w("FarmTimerCache", "Invalid farm updatedAt; rebuilding timers without a revision")
        }
        val cached = Prefs.farmTimerRevision(context)
        val snapshot = if (cached?.canReuse(farmId, mode, updatedAt, now) == true) null
            else FarmTimers.extractSnapshot(context, farm, mode, farmId.toLongOrNull(), now)
        val revision = if (snapshot != null && updatedAt != null) {
            FarmTimerRevision(farmId, mode, updatedAt, now, snapshot.refreshAt)
        } else null
        val details = FarmDetails.extract(farm, now)
        val timers = TimerAlarm.applyFarmFetch(context, farmId, mode, now, revision, snapshot?.timers, baseline, details)
        if (timers == null) {
            Log.i("FarmTimerCache", "Ignoring a farm response after its settings or cache changed")
            return null
        }
        if (baseline != null) FarmPollAlerts.check(context, farmId, FarmDetails.pollState(farm, details, now), baseline)
        FarmReminders.reconcile(context, deliverDue = baseline != null)
        return Refresh(timers, now)
    }
}

/** First wall-clock transition that could change the derived timers without a farm save. */
internal class FarmTimerClock(private val now: Long) {
    var refreshAt: Long = Long.MAX_VALUE
        private set

    fun checkAgainAt(vararg times: Long) {
        for (at in times) if (at > now && at < refreshAt) refreshAt = at
    }
}
