package com.sfl.browser.diagnostics

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.sfl.browser.data.Prefs

internal object CrashReporting {
    const val available = true
    private var crashlytics: FirebaseCrashlytics? = null

    fun start(context: Context) {
        if (Prefs.crashReportingChoice(context) != true) return
        if (FirebaseApp.initializeApp(context) == null) {
            Log.e("CrashReporting", "Firebase could not initialize; crash reporting is unavailable")
            return
        }
        crashlytics = FirebaseCrashlytics.getInstance().also {
            it.setCrashlyticsCollectionEnabled(true)
        }
    }

    fun saveChoice(context: Context, enabled: Boolean) {
        Prefs.setCrashReportingChoice(context, enabled)
        if (!enabled) {
            crashlytics?.let {
                it.setCrashlyticsCollectionEnabled(false)
                it.deleteUnsentReports()
            }
        }
    }
}
