package com.sfl.browser.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sfl.browser.data.Prefs

/**
 * One-shot "a raffle / auction is about to start" reminders.
 *
 * These are separate from [TimerAlarm] because their payload *is* the
 * notification: a raffle start time does not change once announced, so the
 * title and body are carried in the alarm's extras rather than being re-read
 * from farm state at fire time. The alarm is still armed through
 * [TimerAlarm.arm], so exact-alarm permission and the Doze fallback behave
 * exactly as they do for farm timers.
 *
 * Battery-saving mode disarms these too; the poll worker then posts the
 * reminder itself as soon as it sees the start time inside the lead window.
 */
object EventAlarm {

    const val EXTRA_ID = "com.sfl.browser.EVENT_ID"
    const val EXTRA_KIND = "com.sfl.browser.EVENT_KIND"
    const val EXTRA_TITLE = "com.sfl.browser.EVENT_TITLE"
    const val EXTRA_TEXT = "com.sfl.browser.EVENT_TEXT"

    /** Kind markers — also used to build the dedupe key. */
    const val KIND_RAFFLE = "raffle"
    const val KIND_AUCTION = "auction"

    fun reminderKey(kind: String, id: String): String = "$kind:$id:start"

    fun requestCode(key: String): Int = "event:$key".hashCode() and 0x7fffffff

    private fun intent(context: Context, kind: String, id: String, title: String, text: String): Intent =
        Intent(context, EventAlarmReceiver::class.java)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_TEXT, text)

    /** Arm the reminder at [at], or cancel anything already armed for it. */
    fun update(context: Context, kind: String, id: String, title: String, text: String, at: Long) {
        val app = context.applicationContext
        val key = reminderKey(kind, id)
        val piIntent = intent(app, kind, id, title, text)

        if (Prefs.batterySaving(app) || at <= System.currentTimeMillis()) {
            TimerAlarm.disarm(app, piIntent, requestCode(key))
            return
        }
        TimerAlarm.arm(app, piIntent, requestCode(key), at)
    }

    /** Drop the reminder for an event that no longer exists. */
    fun cancel(context: Context, kind: String, id: String) {
        val key = reminderKey(kind, id)
        TimerAlarm.disarm(
            context.applicationContext,
            intent(context, kind, id, "", ""),
            requestCode(key),
        )
    }

    /** Marks the reminder as delivered so the poll does not repeat it. */
    fun markSeen(context: Context, key: String) {
        val seen = Prefs.seenEvents(context)
        seen.put(key, System.currentTimeMillis())
        Prefs.setSeenEvents(context, seen)
    }
}

class EventAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val kind = intent.getStringExtra(EventAlarm.EXTRA_KIND) ?: return
        val id = intent.getStringExtra(EventAlarm.EXTRA_ID) ?: return
        val title = intent.getStringExtra(EventAlarm.EXTRA_TITLE) ?: return
        val text = intent.getStringExtra(EventAlarm.EXTRA_TEXT) ?: return
        if (!Prefs.enableEvents(app)) return

        val key = EventAlarm.reminderKey(kind, id)
        if (Prefs.seenEvents(app).has(key)) return

        Notifier.postEvent(app, key, title, text)
        EventAlarm.markSeen(app, key)
    }
}
