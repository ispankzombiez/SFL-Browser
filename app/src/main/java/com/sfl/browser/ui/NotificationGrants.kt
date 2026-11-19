package com.sfl.browser.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

internal object NotificationGrants {
    fun exactAlarmIntent(context: Context) =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null))

    fun batteryExemptionIntent(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))

    fun isIgnoringBattery(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
}
