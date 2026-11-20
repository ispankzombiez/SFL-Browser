package com.sfl.browser.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.sfl.browser.R
import com.sfl.browser.data.ItemIcons
import com.sfl.browser.ui.MainActivity

/**
 * Posts every local notification the app makes.
 *
 * Design rules:
 *
 *  - **stable IDs.** A timer's notification id is derived from its key, so a
 *    later poll re-posting the same timer *replaces* the old one instead of
 *    stacking a second copy. Same for raffles, auctions and trades.
 *  - **grouping happens at delivery time**, never at scheduling time: the
 *    poller hands over whatever is due together and identical item names that
 *    finish within the configured window collapse into a single notification.
 *  - **icons come from `assets/sfl/`**; items we ship no art for fall back to
 *    the app icon rather than showing nothing.
 */
object Notifier {

    const val CH_TIMERS = "sfl_timers"
    const val CH_EVENTS = "sfl_events"
    const val CH_SALES = "sfl_sales"
    const val CH_PRICES = "sfl_prices"
    const val CH_SYSTEM = "sfl_system"

    private const val GROUP_SFL = "com.sfl.browser.notifications"

    /** Notification ids are non-negative hashes so they can be reused forever. */
    private fun idFor(key: String): Int = key.hashCode() and 0x7fffffff

    // --------------------------------------------------------------- channels

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            channel(CH_TIMERS, context.getString(R.string.channel_timers), NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            channel(CH_EVENTS, context.getString(R.string.channel_events), NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            channel(CH_SALES, context.getString(R.string.channel_sales), NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            channel(CH_PRICES, context.getString(R.string.channel_prices), NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            channel(CH_SYSTEM, context.getString(R.string.channel_errors), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun channel(id: String, name: String, importance: Int): NotificationChannel =
        NotificationChannel(id, name, importance).apply {
            description = name
            enableVibration(true)
        }

    // ---------------------------------------------------------------- timers

    internal fun postTimerGroup(context: Context, group: TimerNotificationGroup) {
        val head = group.timers.first()
        val count = group.timers.size
        val love = head.category == com.sfl.browser.data.TimerCategory.ANIMAL_LOVE
        val queueName = when {
            group.craftingBoxQueue -> context.getString(R.string.category_crafting_box)
            group.cropMachineQueue != null -> context.getString(R.string.cat_crop_machine)
            else -> null
        }
        val title = when {
            head.isMachineOilAlert -> context.getString(R.string.notif_machine_no_oil)
            love && count == 1 -> context.getString(R.string.notif_animal_love, head.label)
            love -> context.getString(R.string.notif_animal_love_group, count, head.label)
            queueName != null -> context.getString(R.string.notif_timer_title, queueName)
            head.category.isFeeding -> if (count == 1) head.label else
                context.getString(R.string.notif_feed_group, head.label, count)
            count == 1 -> context.getString(R.string.notif_timer_title, head.label)
            head.label.equals("Stone", ignoreCase = true) -> context.getString(R.string.notif_stones_ready, count)
            else -> context.getString(R.string.notif_timer_title_count, head.label, count)
        }
        val totals = group.yields
        val text = if (head.isMachineOilAlert) {
            context.getString(R.string.notif_machine_add_oil)
        } else if (love) {
            val format = java.text.NumberFormat.getIntegerInstance()
            group.timers.groupingBy { it.detail }.eachCount().entries.joinToString(", ") {
                context.getString(R.string.notif_item_amount, format.format(it.value), it.key)
            }
        } else if (!totals.isNullOrEmpty()) {
            val format = java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 4 }
            totals.joinToString(", ") { context.getString(R.string.notif_item_amount, format.format(it.amount), it.item) }
        } else if (count == 1) {
            head.detail.ifEmpty { context.getString(R.string.notif_timer_ready) }
        } else {
            context.getString(
                R.string.notif_timer_group_text,
                relative(head.readyAt),
                relative(group.timers.last().readyAt),
            )
        }
        val id = idFor("timer:${head.key}:${head.readyAt}")
        val builder = base(context, CH_TIMERS)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        val art = when {
            group.craftingBoxQueue -> ItemIcons.pathForName(context, "Crafting Box")
            group.cropMachineQueue != null -> ItemIcons.pathForName(context, "Crop Machine")
            else -> head.icon
        }
        icon(context, art)?.let { builder.setLargeIcon(it) }
        post(context, id, builder.build())
    }

    /**
     * Buckets [timers] by label, keeping only items whose ready times fall
     * within [windowMs] of each other inside that label's own run.
     */
    internal fun groupTimers(timers: List<FarmTimer>, windowMs: Long): List<List<FarmTimer>> {
        val sorted = timers.sortedWith(compareBy({ it.label.lowercase() }, { it.readyAt }, { it.key }))
        val groups = ArrayList<List<FarmTimer>>()
        var current = ArrayList<FarmTimer>()

        for (timer in sorted) {
            val sameLabel = current.isNotEmpty() && current.first().label.equals(timer.label, ignoreCase = true)
            val withinWindow = current.isEmpty() || (timer.readyAt - current.first().readyAt) <= windowMs
            if (sameLabel && withinWindow) {
                current += timer
            } else {
                if (current.isNotEmpty()) groups += current
                current = arrayListOf(timer)
            }
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    // --------------------------------------------------------------- events

    internal fun postFarmAlert(context: Context, key: String, title: String, text: String) {
        post(context, idFor("farmAlert:$key"), base(context, CH_TIMERS)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true).build())
    }

    /** Raffles and auctions: one notification per event id. */
    fun postEvent(context: Context, id: String, title: String, text: String) {
        val nid = idFor("event:$id")
        post(
            context,
            nid,
            base(context, CH_EVENTS)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** A listing of ours that sold. */
    fun postSale(context: Context, id: String, title: String, text: String) {
        val nid = idFor("sale:$id")
        post(
            context,
            nid,
            base(context, CH_SALES)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** A watched item crossed its floor-price threshold. */
    fun postPrice(context: Context, id: String, title: String, text: String) {
        val nid = idFor("price:$id")
        post(
            context,
            nid,
            base(context, CH_PRICES)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Poll failures, missing API key, and other "something is wrong" notices. */
    fun postSystem(context: Context, key: String, title: String, text: String) {
        val nid = idFor("system:$key")
        post(
            context,
            nid,
            base(context, CH_SYSTEM)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .build(),
        )
    }

    fun cancel(context: Context, key: String) {
        NotificationManagerCompat.from(context).cancel(idFor(key))
    }

    // --------------------------------------------------------------- plumbing

    @Suppress("DEPRECATION")
    private fun base(context: Context, channelId: String): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            Notification.Builder(context)
                .setPriority(if (channelId == CH_SYSTEM) Notification.PRIORITY_DEFAULT else Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
        }
        return builder.setSmallIcon(R.drawable.ic_stat_sfl)
            .setCategory(Notification.CATEGORY_EVENT)
            .setGroup(GROUP_SFL)
            .setContentIntent(openApp(context))
            .setWhen(System.currentTimeMillis())
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.OPEN_NOTIFICATION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun post(context: Context, id: Int, notification: Notification) {
        // Without POST_NOTIFICATIONS (Android 13+) this throws; permission is
        // requested from the settings screen, so a denied grant is not an error.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    private fun icon(context: Context, path: String?): Bitmap? =
        if (path.isNullOrEmpty()) null else ItemIcons.bitmap(context, path, 192)

    /** "in 4m" / "in 2h" / "now" for a future epoch-ms timestamp. */
    private fun relative(readyAt: Long): String {
        val delta = readyAt - System.currentTimeMillis()
        return when {
            delta <= 0 -> "now"
            delta < 60_000 -> "in ${delta / 1_000}s"
            delta < 3_600_000 -> "in ${delta / 60_000}m"
            delta < 86_400_000 -> "in ${delta / 3_600_000}h"
            else -> "in ${delta / 86_400_000}d"
        }
    }

}
