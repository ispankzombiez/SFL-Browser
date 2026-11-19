package com.sfl.browser

import android.app.Application
import com.sfl.browser.data.HomeTabs
import com.sfl.browser.diagnostics.CrashReporting
import com.sfl.browser.notify.Notifier

/**
 * Notification channels have to exist before anything can post, and they are
 * cheap, so they are created here rather than at the call site.
 *
 * WalletConnect is deliberately *not* initialised here: [com.reown.android.core.CoreClient]
 * does network and database work, and the app should come up even if that fails.
 * It is initialised on the first wallet request from a page.
 */
class SflApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporting.start(this)
        HomeTabs.initialize(this)
        Notifier.ensureChannels(this)
        if (BuildConfig.DEBUG) DebugSeed.ensure(this)
    }
}
