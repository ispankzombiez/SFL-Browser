package com.sfl.browser.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sfl.browser.R
import com.sfl.browser.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

internal enum class FarmReminderKind(val key: String, val label: Int) {
    VIP("vip", R.string.alert_vip),
    CALENDAR("calendar", R.string.alert_calendar),
    WEATHER("weather", R.string.alert_weather),
    SPECIAL("special", R.string.alert_special_events);
}

internal object FarmReminders {
    private const val REQUEST = 120790454
    private const val MINUTE = 60_000L
    private val weatherNames = mapOf(
        "tornado" to "Tornado", "tsunami" to "Tsunami", "fullMoon" to "Full Moon",
        "greatFreeze" to "Great Freeze", "doubleDelivery" to "Double Delivery",
        "bountifulHarvest" to "Bountiful Harvest", "insectPlague" to "Insect Plague",
        "sunshower" to "Sunshower", "fishFrenzy" to "Fish Frenzy",
    )

    fun extract(farm: JSONObject): JSONArray {
        val result = JSONArray()
        fun add(kind: FarmReminderKind, name: String, at: Long) {
            if (at > 0 && name.isNotBlank()) {
                result.put(JSONObject().put("kind", kind.key).put("name", name).put("at", at))
            }
        }
        add(FarmReminderKind.VIP, "VIP", farm.optJSONObject("vip")?.optLong("expiresAt") ?: 0L)
        val calendar = farm.optJSONObject("calendar")
        for (event in calendar?.optJSONArray("dates").farmObjects()) {
            val name = event.optString("name")
            if (name.isBlank() || name == "unknown") continue
            val kind = if (event.optBoolean("weather")) FarmReminderKind.WEATHER else FarmReminderKind.CALENDAR
            val label = event.optString("title").ifBlank { weatherNames[name] ?: name.takeUnless { it == "calendar" }.orEmpty() }
            add(kind, label, dateAt(event.optString("date")) ?: continue)
        }
        for ((name, event) in farm.optJSONObject("specialEvents")?.optJSONObject("current").farmEntries()) {
            if (event.optBoolean("isEligible")) add(FarmReminderKind.SPECIAL, name, event.optLong("startAt"))
        }
        return result
    }

    internal fun dateAt(value: String): Long? = try {
        if (value.length == 10) LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        else Instant.parse(value).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    internal fun window(at: Long, minutes: Int): LongRange {
        val lead = minutes.coerceAtLeast(0) * MINUTE
        // With zero lead the alarm targets the start itself, including a short dispatch grace.
        return if (lead == 0L) at until (at + MINUTE) else (at - lead) until at
    }

    fun schedule(context: Context) = reconcile(context, deliverDue = false)

    @Synchronized
    fun reconcile(context: Context, deliverDue: Boolean) {
        val now = System.currentTimeMillis()
        val farm = Prefs.farmId(context)
        val intent = Intent(context, FarmReminderReceiver::class.java)
        val stored = Prefs.farmDetails(context).optJSONArray("reminders")
        val seen = Prefs.farmRemindersSeen(context)
        val nextSeen = JSONObject()
        for (id in seen.keys()) {
            val expires = seen.optLong(id)
            if (expires > now) nextSeen.put(id, expires)
        }
        val due = mutableListOf<Triple<String, String, String>>()
        var next = Long.MAX_VALUE
        for (item in stored.farmObjects()) {
            val kind = FarmReminderKind.values().firstOrNull { it.key == item.optString("kind") } ?: continue
            val name = item.optString("name").takeIf(String::isNotBlank) ?: continue
            val at = item.optLong("at").takeIf { it > 0 } ?: continue
            val id = "${kind.key}:$at:$name"
            val minutes = Prefs.farmAlertLead(context, kind.key)
            val window = window(at, minutes)
            if (now > window.last || nextSeen.has(id)) continue
            if (!Prefs.farmAlertEnabled(context, kind.key)) continue
            if (deliverDue && now in window) {
                val title = if (kind == FarmReminderKind.VIP) context.getString(R.string.alert_vip_soon)
                    else context.getString(R.string.alert_event_soon, name)
                val time = java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT,
                ).format(java.util.Date(at))
                due += Triple(id, title, context.getString(R.string.alert_upcoming_time, time,
                    context.getString(kind.label)))
                nextSeen.put(id, at + MINUTE)
            } else {
                next = minOf(next, maxOf(window.first, now + 1))
            }
        }
        if (!Prefs.setFarmRemindersSeen(context, farm, nextSeen)) return
        if (!Prefs.batterySaving(context) && next != Long.MAX_VALUE) {
            TimerAlarm.arm(context, intent, REQUEST, next)
        } else TimerAlarm.disarm(context, intent, REQUEST)
        for ((id, title, text) in due) Notifier.postFarmAlert(context, id, title, text)
    }
}

class FarmReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FarmReminders.reconcile(context, deliverDue = true)
    }
}
