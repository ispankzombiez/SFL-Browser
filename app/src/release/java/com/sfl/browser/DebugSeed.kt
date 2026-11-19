package com.sfl.browser

import android.content.Context

// Lets shared startup code compile without packaging the debug fixtures.
internal object DebugSeed {
    @Suppress("UNUSED_PARAMETER")
    fun ensure(context: Context) = Unit
}
