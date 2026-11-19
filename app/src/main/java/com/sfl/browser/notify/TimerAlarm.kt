package com.sfl.browser.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.sfl.browser.data.Prefs
import com.sfl.browser.ui.MainActivity

/**
 * Exact-time delivery for farm timers.
 *
 * Each group gets one alarm at its last timer's ready time plus offset. The poller
 * re-arms them every run: if the ready time moved (a crop was replanted, a
 * boost landed) the old alarm is cancelled and a new one set, and alarms for
 * timers that no longer exist are dropped.
 *
 * Two constraints shape the implementation:
 *
 *  - **`SCHEDULE_EXACT_ALARM` is not guaranteed.** From Android 12 the user can
 *    revoke it, so every set is gated on [AlarmManager.canScheduleExactAlarms]
 *    and falls back to [AlarmManager.setAndAllowWhileIdle] — still wakes the
 *    device from Doze, just not to the exact second.
 *  - **Alarms fire on the main thread with a strict time budget**, so the
 *    receiver only reads SharedPreferences and posts a notification; the API
 *    is never touched from here.
 *
 * When battery saving is on, [scheduleAll] disarms everything and the poll
 * worker delivers whatever is due instead.
 */
object TimerAlarm {

    const val EXTRA_KEY = "com.sfl.browser.TIMER_KEY"

    fun canScheduleExact(context: Context): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    // ------------------------------------------------------------- arming

    /**
     * Reconcile every alarm against the most recent poll's timers.
     * Safe to call from the worker, the boot receiver and the settings screen.
     */
    @Synchronized
    fun scheduleAll(context: Context, forceRearm: Boolean = false) =
        scheduleAll(context, forceRearm, FarmTimers.fromJson(Prefs.timersJson(context)))

    private fun scheduleAll(context: Context, forceRearm: Boolean, timers: List<FarmTimer>) {
        val app = context.applicationContext
        val scheduled = Prefs.scheduled(app)

        if (Prefs.batterySaving(app)) {
            // Nothing alarmed: cancel whatever we armed before and let the poll
            // worker deliver the batch.
            val stale = scheduled.keys()
            while (stale.hasNext()) cancel(app, stale.next())
            Prefs.setScheduled(app, org.json.JSONObject())
            return
        }

        val groups = planned(app, timers, Prefs.fired(app))
        val now = System.currentTimeMillis()
        val keep = org.json.JSONObject()

        for (group in groups) {
            val at = group.fireAt
            val key = group.alarmKey
            val armedAt = scheduled.optLong(key, 0L)
            if (!forceRearm && armedAt == at) {
                keep.put(key, at)
                continue
            }
            if (at <= now && armedAt == 0L) continue // The poll handles newly discovered overdue groups.
            if (armedAt != 0L || forceRearm) cancel(app, key)
            set(app, key, at)
            keep.put(key, at)
        }

        // Anything armed for a timer that has disappeared must go.
        val previouslyArmed = scheduled.keys()
        while (previouslyArmed.hasNext()) {
            val key = previouslyArmed.next()
            if (!keep.has(key)) cancel(app, key)
        }

        Prefs.setScheduled(app, keep)
    }

    /** Cancel one timer's alarm (called when it fires or the timer vanishes). */
    fun cancel(context: Context, key: String) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(pending(context, key))
    }

    private fun set(context: Context, key: String, at: Long) =
        arm(context, Intent(context, TimerAlarmReceiver::class.java).putExtra(EXTRA_KEY, key),
            key.hashCode() and 0x7fffffff, at)

    /**
     * Arm any broadcast [intent] at [at], honouring the exact-alarm permission.
     * Shared with the raffle/auction reminders so they obey the same rules.
     */
    fun arm(context: Context, intent: Intent, requestCode: Int, at: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (canScheduleExact(context)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                // No exact-alarm permission: still wake from Doze, just late.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Cancel whatever [intent] + [requestCode] armed. */
    fun disarm(context: Context, intent: Intent, requestCode: Int) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.cancel(pi)
    }

    private fun pending(context: Context, key: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            key.hashCode() and 0x7fffffff,
            Intent(context, TimerAlarmReceiver::class.java).putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // ---------------------------------------------------------- delivering

    @Synchronized
    internal fun applyFarmFetch(
        context: Context,
        farmId: String,
        cookingMode: Int,
        fetchedAt: Long,
        revision: FarmTimerRevision?,
        changed: List<FarmTimer>?,
        baseline: Boolean?,
    ): List<FarmTimer>? {
        if (!Prefs.recordFarmFetch(context, farmId, cookingMode, fetchedAt, revision, changed?.let(FarmTimers::toJson))) {
            return null
        }
        val timers = changed ?: FarmTimers.fromJson(Prefs.timersJson(context))
        if (baseline != null) {
            val fired = Prefs.fired(context)
            val live = timers.mapTo(hashSetOf()) { it.key }
            val keys = fired.keys()
            var pruned = false
            while (keys.hasNext()) {
                if (keys.next() !in live) {
                    keys.remove()
                    pruned = true
                }
            }
            if (pruned) Prefs.setFired(context, fired)
            deliverDue(context, baseline, timers)
        } else if (changed != null) {
            scheduleAll(context, forceRearm = false, timers = timers)
        }
        return timers
    }

    private fun planned(
        context: Context, timers: List<FarmTimer>, fired: org.json.JSONObject,
    ): List<TimerNotificationGroup> =
        planTimerNotifications(
            timers.filter { Prefs.timerCategoryEnabled(context, it.category) },
            Prefs.groupWindowMillis(context), Prefs.offsetMillis(context), System.currentTimeMillis(),
            craftingBoxAllDone = Prefs.craftingBoxAllDone(context),
            cropMachineAllDone = Prefs.cropMachineAllDone(context),
        ) { key, at -> fired.has(key) && fired.optLong(key) == at }

    @Synchronized
    fun deliverDue(context: Context, baseline: Boolean = false) =
        deliverDue(context, baseline, FarmTimers.fromJson(Prefs.timersJson(context)))

    private fun deliverDue(context: Context, baseline: Boolean, timers: List<FarmTimer>) {
        val app = context.applicationContext
        val fired = Prefs.fired(app)
        val now = System.currentTimeMillis()
        val due = planned(app, timers, fired).filter { it.fireAt <= now }
        due.forEach { group ->
            group.timers.forEach { timer -> fired.put(timer.key, timer.notificationStamp(Prefs.offsetMillis(app))) }
        }
        if (due.isNotEmpty()) Prefs.setFired(app, fired)
        scheduleAll(app, forceRearm = false, timers = timers)
        if (!baseline) due.forEach { Notifier.postTimerGroup(app, it) }
    }
}

/**
 * Fired by [TimerAlarm] at a timer's ready time. Registered in the manifest
 * with `android:exported="false"` — nothing outside the app can fire it.
 */
class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TimerAlarm.deliverDue(context)
    }
}

/**
 * Android clears alarms on reboot, so re-arm everything (and the poll chain)
 * once the device comes back up. Also runs after an app update, which can
 * clear alarms too.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        PollScheduler.schedule(context.applicationContext)
        TimerAlarm.scheduleAll(context.applicationContext, forceRearm = true)
    }
}

/** Opens the browser UI; kept here so alarm and notification intents match. */
fun openAppIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
