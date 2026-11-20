package com.sfl.browser.data

import android.content.Context
import android.content.SharedPreferences
import com.sfl.browser.BuildConfig
import com.sfl.browser.notify.FarmTimerRevision
import com.sfl.browser.notify.MarketplaceTradeAlert
import com.sfl.browser.notify.MarketplaceTradeAlerts
import org.json.JSONArray
import org.json.JSONObject

/** One user-defined floor-price alert, resolved through `type=tradeable`. */
data class PriceAlert(
    val name: String,
    val collection: String,
    val id: Int,
    val below: Boolean,
    val threshold: Double,
    /** False once it has fired; it re-arms when the floor crosses back over. */
    val armed: Boolean = true,
)

/**
 * Every user setting plus the small amount of bookkeeping the poller needs:
 * which timers already have an alarm, which have already been delivered, and
 * what we saw last time so a first run does not spam notifications.
 *
 * Plain SharedPreferences holding JSON blobs — the state is a few kilobytes and
 * only ever touched from the poll worker or an alarm receiver, so a database
 * would be pure overhead.
 */
object Prefs {

    private const val FILE = "sfl_prefs"

    /** Indices into `@array/time_units` (seconds / minutes / hours). */
    const val UNIT_SECONDS = 0
    const val UNIT_MINUTES = 1
    const val UNIT_HOURS = 2

    /** Indices into `@array/cooking_modes`. */
    const val COOKING_EACH = 0
    const val COOKING_BUILDING = 1

    const val DEFAULT_HOME_URL = "https://sunflower-land.com/play/?ref=iSPANK"
    private const val LEGACY_HOME_URL = "https://sunflower-land.com/play"
    private const val K_HOME_URL = "homeUrl"
    const val DEFAULT_POLL_VALUE = 15
    const val DEFAULT_POLL_UNIT = UNIT_MINUTES
    const val DEFAULT_OFFSET_VALUE = 0
    const val DEFAULT_OFFSET_UNIT = UNIT_MINUTES
    const val DEFAULT_GROUP_VALUE = 2
    const val DEFAULT_GROUP_UNIT = UNIT_MINUTES
    const val DEFAULT_LEAD_VALUE = 10
    const val DEFAULT_LEAD_UNIT = UNIT_MINUTES

    /**
     * The API allows roughly one request per five seconds per IP and each poll
     * makes several, so a sub-30s interval would only ever queue up behind the
     * throttle.
     */
    const val MIN_INTERVAL_SECONDS = 30L

    // Keys for the JSON state blobs.
    private const val K_TIMERS = "state.timers"
    private const val K_LAST_FETCHED = "state.lastFetchedAt"
    private const val K_FARM_DETAILS = "state.farmDetails"
    private const val K_FARM_POLL_MARKS = "state.farmPollMarks"
    private const val K_FARM_REMINDERS_SEEN = "state.farmRemindersSeen"
    private const val K_TIMER_REVISION = "state.timerRevision"
    private const val K_SCHEDULED = "state.scheduled"
    private const val K_FIRED = "state.fired"
    private const val K_SEEN_EVENTS = "state.seenEvents"
    private const val K_SEEN_SALES = "state.seenSales"
    private const val K_ALERTS = "state.alerts"
    private const val K_BASELINE = "state.baselineDone"

    private val lock = Any()

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ------------------------------------------------------------- API / farm

    /** The Community API key. Encrypted at rest by [SecureStore], never in the APK. */
    fun apiKey(context: Context): String? =
        SecureStore.get(context)?.takeIf { it.isNotBlank() }

    fun hasApiKey(context: Context): Boolean = apiKey(context) != null

    fun farmId(context: Context): String = sp(context).getString("farmId", "") ?: ""

    fun setFarmId(context: Context, value: String) = synchronized(lock) {
        val normalized = value.trim()
        if (farmId(context) != normalized) {
            sp(context).edit().putString("farmId", normalized)
                .remove(K_TIMER_REVISION).remove(K_LAST_FETCHED)
                .remove(K_FARM_DETAILS).remove(K_FARM_POLL_MARKS).remove(K_FARM_REMINDERS_SEEN)
                .remove(K_SEEN_SALES).apply()
        }
    }

    // --------------------------------------------------------------- browser

    private const val K_NOTIFICATIONS_ONLY = "notificationsOnly"
    private const val K_DISCLAIMER_ANSWERED = "disclaimerAnswered"
    private const val K_NOTIFICATION_ONBOARDING_STEP = "notificationOnboardingStep"
    private const val K_NOTIFICATION_APP = "notificationApp"
    private const val K_CRASH_REPORTING_CHOICE = "crashReportingChoice"

    fun disclaimerAnswered(context: Context): Boolean = sp(context).getBoolean(K_DISCLAIMER_ANSWERED, false)

    fun notificationOnboardingStep(context: Context): String? =
        sp(context).getString(K_NOTIFICATION_ONBOARDING_STEP, null)

    fun setNotificationOnboardingStep(context: Context, step: String) {
        sp(context).edit().putString(K_NOTIFICATION_ONBOARDING_STEP, step).apply()
    }

    fun crashReportingChoice(context: Context): Boolean? = sp(context).let {
        if (it.contains(K_CRASH_REPORTING_CHOICE)) it.getBoolean(K_CRASH_REPORTING_CHOICE, false) else null
    }

    fun setCrashReportingChoice(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(K_CRASH_REPORTING_CHOICE, enabled).apply()
    }

    fun recordDisclaimerChoice(context: Context, notificationsOnly: Boolean) {
        sp(context).edit().putBoolean(K_DISCLAIMER_ANSWERED, true)
            .putBoolean(K_NOTIFICATIONS_ONLY, notificationsOnly).apply()
    }

    fun notificationsOnly(context: Context): Boolean = sp(context).getBoolean(K_NOTIFICATIONS_ONLY, false)

    fun setNotificationsOnly(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(K_NOTIFICATIONS_ONLY, enabled).apply()
    }

    fun observeBrowserMode(context: Context, onChange: () -> Unit): () -> Unit {
        val prefs = sp(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == K_NOTIFICATIONS_ONLY) onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun notificationApp(context: Context): String? = sp(context).getString(K_NOTIFICATION_APP, null)

    fun setNotificationApp(context: Context, packageName: String?) {
        sp(context).edit().putString(K_NOTIFICATION_APP, packageName).apply()
    }

    internal fun initializeStarterHomeUrl(context: Context) {
        val saved = sp(context).getString(K_HOME_URL, null)
        val home = starterHomeUrl(saved)
        if (home != saved) setHomeUrl(context, home)
    }

    internal fun starterHomeUrl(saved: String?): String =
        if (saved == null || saved == LEGACY_HOME_URL) DEFAULT_HOME_URL else saved

    fun homeUrl(context: Context): String =
        sp(context).getString(K_HOME_URL, DEFAULT_HOME_URL) ?: DEFAULT_HOME_URL

    fun setHomeUrl(context: Context, value: String) {
        val trimmed = value.trim().ifEmpty { DEFAULT_HOME_URL }
        sp(context).edit().putString(K_HOME_URL, trimmed).apply()
    }

    // ---------------------------------------------------------------- wallet

    // --------------------------------------------------------------- polling

    fun pollValue(context: Context): Int = sp(context).getInt("pollValue", DEFAULT_POLL_VALUE)

    fun pollUnit(context: Context): Int = sp(context).getInt("pollUnit", DEFAULT_POLL_UNIT)

    fun setPoll(context: Context, value: Int, unit: Int) {
        sp(context).edit().putInt("pollValue", value.coerceAtLeast(1)).putInt("pollUnit", unit).apply()
    }

    /** Poll period in seconds, clamped to the API's sustainable minimum. */
    fun intervalSeconds(context: Context): Long {
        val raw = pollValue(context).toLong() * unitMillis(pollUnit(context)) / 1000L
        return raw.coerceAtLeast(MIN_INTERVAL_SECONDS)
    }

    fun wifiOnly(context: Context): Boolean = sp(context).getBoolean("wifiOnly", false)

    fun setWifiOnly(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("wifiOnly", value).apply()
    }

    // -------------------------------------------------------------- delivery

    /** When true, no alarms are set; everything due waits for the next poll. */
    fun batterySaving(context: Context): Boolean = sp(context).getBoolean("batterySaving", false)

    fun setBatterySaving(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("batterySaving", value).apply()
    }

    /** Signed: negative shifts notifications earlier, positive later. */
    fun offsetValue(context: Context): Int = sp(context).getInt("offsetValue", DEFAULT_OFFSET_VALUE)

    fun offsetUnit(context: Context): Int = sp(context).getInt("offsetUnit", DEFAULT_OFFSET_UNIT)

    fun setOffset(context: Context, value: Int, unit: Int) {
        sp(context).edit().putInt("offsetValue", value).putInt("offsetUnit", unit).apply()
    }

    fun offsetMillis(context: Context): Long =
        offsetValue(context).toLong() * unitMillis(offsetUnit(context))

    fun ignoreBatteryAsked(context: Context): Boolean =
        sp(context).getBoolean("ignoreBattery", false)

    fun setIgnoreBatteryAsked(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("ignoreBattery", value).apply()
    }

    // -------------------------------------------------------------- grouping

    fun groupValue(context: Context): Int = sp(context).getInt("groupValue", DEFAULT_GROUP_VALUE)

    fun hideBrowserControls(context: Context): Boolean = sp(context).getBoolean("hideBrowserControls", false)

    fun setHideBrowserControls(context: Context, enabled: Boolean) {
        val prefs = sp(context)
        // Preserve the old combined setting before the browser preference changes.
        prefs.edit().putBoolean("hideAndroidNavigation", hideAndroidNavigation(context))
            .putBoolean("hideBrowserControls", enabled).apply()
    }

    fun hideAndroidNavigation(context: Context): Boolean =
        sp(context).getBoolean("hideAndroidNavigation", hideBrowserControls(context))

    fun setHideAndroidNavigation(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean("hideAndroidNavigation", enabled).apply()
    }

    fun groupUnit(context: Context): Int = sp(context).getInt("groupUnit", DEFAULT_GROUP_UNIT)

    fun setGroupWindow(context: Context, value: Int, unit: Int) {
        sp(context).edit().putInt("groupValue", value.coerceAtLeast(0)).putInt("groupUnit", unit).apply()
    }

    /** Same-named items finishing within this span collapse into one notification. */
    fun groupWindowMillis(context: Context): Long =
        (groupValue(context).toLong() * unitMillis(groupUnit(context))).coerceAtLeast(0L)

    // -------------------------------------------------------------- triggers

    fun enableTimers(context: Context): Boolean = sp(context).getBoolean("enableTimers", true)

    fun setEnableTimers(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("enableTimers", value).apply()
    }

    fun timerCategoryEnabled(context: Context, category: TimerCategory): Boolean =
        category != TimerCategory.DISPLAY_ONLY &&
            sp(context).getBoolean("enableTimerCategory.${category.key}",
                category == TimerCategory.ANIMAL_LOVE || category.isFeeding ||
                    category == TimerCategory.CROP_MACHINE || category.isAgingShed || enableTimers(context))

    internal fun farmAlertEnabled(context: Context, key: String): Boolean =
        sp(context).getBoolean("farmAlert.$key", true)

    internal fun setFarmAlertEnabled(context: Context, key: String, enabled: Boolean) {
        sp(context).edit().putBoolean("farmAlert.$key", enabled).apply()
    }

    internal fun farmAlertLead(context: Context, key: String): Int =
        sp(context).getInt("farmAlertLead.$key", DEFAULT_LEAD_VALUE)

    internal fun setFarmAlertLead(context: Context, key: String, minutes: Int) {
        sp(context).edit().putInt("farmAlertLead.$key", minutes.coerceAtLeast(0)).apply()
    }

    internal fun dailyResetDelivered(context: Context): Long =
        sp(context).getLong("state.dailyResetDelivered", Long.MIN_VALUE)

    internal fun setDailyResetDelivered(context: Context, at: Long) {
        sp(context).edit().putLong("state.dailyResetDelivered", at).apply()
    }

    fun setTimerCategoryEnabled(context: Context, category: TimerCategory, value: Boolean) {
        sp(context).edit().putBoolean("enableTimerCategory.${category.key}", value).apply()
    }

    fun cookingMode(context: Context): Int = sp(context).getInt("cookingMode", COOKING_EACH)

    fun setCookingMode(context: Context, value: Int) = synchronized(lock) {
        if (cookingMode(context) != value) {
            sp(context).edit().putInt("cookingMode", value)
                .remove(K_TIMER_REVISION).remove(K_LAST_FETCHED).apply()
        }
    }

    fun craftingBoxAllDone(context: Context): Boolean = sp(context).getBoolean("craftingBoxAllDone", false)

    fun setCraftingBoxAllDone(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("craftingBoxAllDone", value).apply()
    }

    fun cropMachineAllDone(context: Context): Boolean = sp(context).getBoolean("cropMachineAllDone", false)

    fun setCropMachineAllDone(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("cropMachineAllDone", value).apply()
    }

    fun enableEvents(context: Context): Boolean = sp(context).getBoolean("enableEvents", true)

    fun setEnableEvents(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("enableEvents", value).apply()
    }

    fun leadValue(context: Context): Int = sp(context).getInt("leadValue", DEFAULT_LEAD_VALUE)

    fun leadUnit(context: Context): Int = sp(context).getInt("leadUnit", DEFAULT_LEAD_UNIT)

    fun setLead(context: Context, value: Int, unit: Int) {
        sp(context).edit().putInt("leadValue", value.coerceAtLeast(0)).putInt("leadUnit", unit).apply()
    }

    fun leadMillis(context: Context): Long =
        (leadValue(context).toLong() * unitMillis(leadUnit(context))).coerceAtLeast(0L)

    fun enableSales(context: Context): Boolean = sp(context).getBoolean("enableSales", true)

    fun setEnableSales(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("enableSales", value).apply()
    }

    fun enableAcceptedOffers(context: Context): Boolean =
        sp(context).getBoolean("enableAcceptedOffers", true)

    fun setEnableAcceptedOffers(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("enableAcceptedOffers", value).apply()
    }

    fun enablePrices(context: Context): Boolean = sp(context).getBoolean("enablePrices", true)

    fun setEnablePrices(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("enablePrices", value).apply()
    }

    // ----------------------------------------------------------- price alerts

    fun batchedPriceChecks(context: Context): Boolean = sp(context).getBoolean("batchedPriceChecks", true)

    fun setBatchedPriceChecks(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("batchedPriceChecks", value).apply()
    }

    fun alerts(context: Context): List<PriceAlert> = synchronized(lock) {
        val raw = sp(context).getString(K_ALERTS, null) ?: return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                add(
                    PriceAlert(
                        name = o.optString("name"),
                        collection = o.optString("collection", "collectibles"),
                        id = o.optInt("id", -1),
                        below = o.optBoolean("below", true),
                        threshold = o.optDouble("threshold", 0.0),
                        armed = o.optBoolean("armed", true),
                    )
                )
            }
        }
    }

    fun saveAlerts(context: Context, alerts: List<PriceAlert>) {
        val array = JSONArray()
        alerts.forEach { a ->
            array.put(
                JSONObject()
                    .put("name", a.name)
                    .put("collection", a.collection)
                    .put("id", a.id)
                    .put("below", a.below)
                    .put("threshold", a.threshold)
                    .put("armed", a.armed)
            )
        }
        writeBlob(context, K_ALERTS, array.toString())
    }

    /** Don't restore an alert edited or removed while its quote was being fetched. */
    fun updatePriceAlertArming(context: Context, expected: PriceAlert, armed: Boolean): Boolean =
        synchronized(lock) {
            val latest = alerts(context).toMutableList()
            val index = latest.indexOf(expected)
            if (index < 0) return@synchronized false
            latest[index] = expected.copy(armed = armed)
            saveAlerts(context, latest)
            true
        }

    // ------------------------------------------------------------------ state

    /** Timers from the most recent poll; the alarm receiver reads from this. */
    fun timersJson(context: Context): JSONArray = readArray(context, K_TIMERS)

    fun setTimersJson(context: Context, value: JSONArray) = synchronized(lock) {
        sp(context).edit().putString(K_TIMERS, value.toString()).remove(K_TIMER_REVISION).apply()
    }

    internal fun farmTimerRevision(context: Context): FarmTimerRevision? {
        val revision = readObject(context, K_TIMER_REVISION)
        if (revision.optInt("appVersion", -1) != BuildConfig.VERSION_CODE) return null
        return FarmTimerRevision.fromJson(revision)
    }

    internal fun recordFarmFetch(
        context: Context,
        farmId: String,
        cookingMode: Int,
        fetchedAt: Long,
        revision: FarmTimerRevision?,
        timers: JSONArray?,
        details: JSONObject? = null,
    ): Boolean = synchronized(lock) {
        if (farmId(context) != farmId || cookingMode(context) != cookingMode ||
            (timers == null && !sp(context).contains(K_TIMER_REVISION))
        ) return@synchronized false
        val edit = sp(context).edit().putLong(K_LAST_FETCHED, fetchedAt)
        if (details != null) edit.putString(K_FARM_DETAILS, details.toString())
        if (timers != null) {
            edit.putString(K_TIMERS, timers.toString())
            if (revision == null) edit.remove(K_TIMER_REVISION)
            else edit.putString(K_TIMER_REVISION, revision.toJson().put("appVersion", BuildConfig.VERSION_CODE).toString())
        }
        edit.apply()
        true
    }

    internal fun farmDetails(context: Context): JSONObject = readObject(context, K_FARM_DETAILS)

    internal fun replaceFarmPollMarks(context: Context, farm: String, marks: JSONObject): JSONObject? =
        synchronized(lock) {
            if (farm != farmId(context)) return@synchronized null
            val previous = readObject(context, K_FARM_POLL_MARKS)
            // A missing API section is not proof that its tracked state has reset.
            val merged = JSONObject(previous.toString())
            for (key in marks.keys()) {
                if (key == "sick" || key == "projects") {
                    val herds = merged.optJSONObject(key) ?: JSONObject()
                    val current = marks.getJSONObject(key)
                    for (herd in current.keys()) herds.put(herd, current.get(herd))
                    merged.put(key, herds)
                } else merged.put(key, marks.get(key))
            }
            writeBlob(context, K_FARM_POLL_MARKS, merged.toString())
            previous
        }

    internal fun farmRemindersSeen(context: Context): JSONObject = readObject(context, K_FARM_REMINDERS_SEEN)

    internal fun setFarmRemindersSeen(context: Context, farm: String, value: JSONObject): Boolean = synchronized(lock) {
        if (farm != farmId(context)) return@synchronized false
        writeBlob(context, K_FARM_REMINDERS_SEEN, value.toString())
        true
    }

    /**
     * Epoch ms of the last successful farm fetch, including unchanged responses.
     * Separate from the farm's API save timestamp.
     */
    fun lastFetchedAt(context: Context): Long = sp(context).getLong(K_LAST_FETCHED, 0L)

    fun setLastFetchedAt(context: Context, value: Long) {
        sp(context).edit().putLong(K_LAST_FETCHED, value).apply()
    }

    /** key -> epoch ms at which that timer's alarm is set. */
    fun scheduled(context: Context): JSONObject = readObject(context, K_SCHEDULED)

    fun setScheduled(context: Context, value: JSONObject) = writeBlob(context, K_SCHEDULED, value.toString())

    /** key -> epoch ms at which that timer was delivered (or baseline-recorded). */
    fun fired(context: Context): JSONObject = readObject(context, K_FIRED)

    fun setFired(context: Context, value: JSONObject) = writeBlob(context, K_FIRED, value.toString())

    /** raffle / auction ids we have already announced. */
    fun seenEvents(context: Context): JSONObject = readObject(context, K_SEEN_EVENTS)

    fun setSeenEvents(context: Context, value: JSONObject) =
        writeBlob(context, K_SEEN_EVENTS, value.toString())

    internal fun recordMarketplaceTrades(
        context: Context, farm: String, trades: JSONArray, baseline: Boolean,
    ): List<MarketplaceTradeAlert> = synchronized(lock) {
        if (farm != farmId(context)) return@synchronized emptyList()
        val previous = readObject(context, K_SEEN_SALES).takeIf { sp(context).contains(K_SEEN_SALES) }
        val scan = MarketplaceTradeAlerts.scan(trades, farm, previous, baseline)
        writeBlob(context, K_SEEN_SALES, scan.seen.toString())
        scan.alerts
    }

    /**
     * True once the first poll has recorded the current state silently. Without
     * it the very first run would announce every crop that is already ripe.
     */
    fun baselineDone(context: Context): Boolean = sp(context).getBoolean(K_BASELINE, false)

    fun setBaselineDone(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(K_BASELINE, value).apply()
    }

    // --------------------------------------------------------------- helpers

    fun unitMillis(unit: Int): Long = when (unit) {
        UNIT_SECONDS -> 1_000L
        UNIT_HOURS -> 3_600_000L
        else -> 60_000L
    }

    private fun readObject(context: Context, key: String): JSONObject = synchronized(lock) {
        val raw = sp(context).getString(key, null) ?: return JSONObject()
        try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun readArray(context: Context, key: String): JSONArray = synchronized(lock) {
        val raw = sp(context).getString(key, null) ?: return JSONArray()
        try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun writeBlob(context: Context, key: String, value: String) = synchronized(lock) {
        sp(context).edit().putString(key, value).apply()
    }
}
