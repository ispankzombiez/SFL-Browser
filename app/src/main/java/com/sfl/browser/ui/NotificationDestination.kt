package com.sfl.browser.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import com.sfl.browser.R
import com.sfl.browser.data.Prefs
import java.text.Collator

internal object NotificationDestination {
    data class App(val packageName: String, val label: String)

    fun installedApps(activity: Activity): List<App> =
        activity.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .asSequence()
            .filter { it.activityInfo.exported && it.activityInfo.packageName != activity.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { App(it.activityInfo.packageName, it.activityInfo.applicationInfo.loadLabel(activity.packageManager).toString()) }
            .sortedWith(compareBy(Collator.getInstance()) { it.label })
            .toList()

    fun openSelectedApp(activity: Activity): Boolean {
        val selected = Prefs.notificationApp(activity) ?: return false
        val launch = activity.packageManager.getLaunchIntentForPackage(selected)
        if (launch != null && activity.tryLaunch(launch)) return true
        Toast.makeText(activity, R.string.notification_app_open_failed, Toast.LENGTH_SHORT).show()
        return false
    }

    fun openFarmLink(activity: Activity, url: String) {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        val selected = Prefs.notificationApp(activity)
        if (selected != null) {
            if (activity.tryLaunch(Intent(link).setPackage(selected))) return
            Toast.makeText(activity, R.string.notification_app_link_failed, Toast.LENGTH_SHORT).show()
        }

        val manager = activity.packageManager
        // Resolve a generic web URL rather than a game app that claims this particular link.
        val defaultBrowser = Intent(link).setData(Uri.parse("https://example.com/")).resolveActivity(manager)
        if (defaultBrowser != null && defaultBrowser.packageName != activity.packageName &&
            activity.tryLaunch(Intent(link).setPackage(defaultBrowser.packageName))
        ) return

        // Do not loop back into SFL Browser if it is the system default or appears in the chooser.
        val handlers = manager.queryIntentActivities(link, PackageManager.MATCH_DEFAULT_ONLY)
        if (handlers.any { it.activityInfo.packageName != activity.packageName }) {
            val own = handlers.filter { it.activityInfo.packageName == activity.packageName }
                .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }.toTypedArray()
            if (activity.tryLaunch(Intent.createChooser(link, null).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, own))) return
        }
        Toast.makeText(activity, R.string.settings_link_unavailable, Toast.LENGTH_SHORT).show()
    }

    private fun Activity.tryLaunch(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
