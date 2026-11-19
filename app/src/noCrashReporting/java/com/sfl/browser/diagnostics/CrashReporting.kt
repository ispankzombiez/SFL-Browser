package com.sfl.browser.diagnostics

import android.content.Context

@Suppress("UNUSED_PARAMETER")
internal object CrashReporting {
    const val available = false

    fun start(context: Context) = Unit

    fun saveChoice(context: Context, enabled: Boolean) = Unit
}
