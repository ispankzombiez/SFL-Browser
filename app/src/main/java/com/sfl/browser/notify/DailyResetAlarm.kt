package com.sfl.browser.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sfl.browser.R
import com.sfl.browser.data.Prefs

internal object DailyResetAlarm {
    const val KEY = "dailyReset"
    private const val AT = "resetAt"
    private const val REQUEST = 120790453

    private fun intent(context: Context) = Intent(context, DailyResetReceiver::class.java)

    @Synchronized
    fun schedule(context: Context, now: Long = System.currentTimeMillis()) {
        if (!Prefs.farmAlertEnabled(context, KEY)) {
            for (slot in 0..1) TimerAlarm.disarm(context, intent(context).setAction(KEY + slot), REQUEST)
            return
        }
        val at = FarmDay.nextReset(now)
        // Keep tomorrow's alarm separate from a pending delivery for today.
        TimerAlarm.arm(context, intent(context).setAction(KEY + Math.floorMod(FarmDay.index(at), 2L)).putExtra(AT, at),
            REQUEST, at)
    }

    @Synchronized
    fun receive(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        val at = intent.getLongExtra(AT, 0)
        if (Prefs.farmAlertEnabled(context, KEY) && shouldDeliver(at, now, Prefs.dailyResetDelivered(context))) {
            Prefs.setDailyResetDelivered(context, at)
            Notifier.postFarmAlert(context, KEY, context.getString(R.string.alert_daily_reset),
                context.getString(R.string.alert_daily_reset_message))
        }
        schedule(context, now)
    }

    internal fun shouldDeliver(at: Long, now: Long, lastDelivered: Long): Boolean =
        at > 0 && at == FarmDay.start(now) && at > lastDelivered
}

class DailyResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = DailyResetAlarm.receive(context, intent)
}
