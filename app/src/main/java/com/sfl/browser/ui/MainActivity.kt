package com.sfl.browser.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sfl.browser.diagnostics.CrashReporting
import com.sfl.browser.R
import com.sfl.browser.data.HomeTabs
import com.sfl.browser.data.HomeTab
import com.sfl.browser.data.Prefs
import com.sfl.browser.notify.PollScheduler
import com.sfl.browser.notify.TimerAlarm
import com.sfl.browser.overview.OverviewActivity
import com.sfl.browser.overview.FarmTasksActivity
import com.sfl.browser.overview.TimelineActivity
import com.sfl.browser.wallet.EthereumBridge
import com.sfl.browser.wallet.WalletConnectManager
import org.json.JSONObject
import java.net.URLEncoder

/**
 * The browser: chrome (url bar, back/forward, menu) plus the WebView with the
 * EIP-1193 provider injected into it.
 *
 * Two things run on every start: the notification poller is (re)armed — the
 * app may have been force-stopped, which cancels WorkManager's schedule — and
 * website wallet requests are wired to the session the SDK restores.
 */
class MainActivity : PaletteActivity() {

    private data class BrowserTab(val view: WebView, val bridge: EthereumBridge, val homeUrl: String)

    private enum class NotificationOnboardingStep { NOTIFICATIONS, EXACT_ALARM, BATTERY, SETUP_REMINDER, DONE }

    private val openTabs = linkedMapOf<String, BrowserTab>()
    private val savedTabs = mutableMapOf<String, Bundle>()
    private var activeTab = HOME_TAB
    private val webView: WebView get() = openTabs.getValue(activeTab).view
    private lateinit var webTabs: FrameLayout
    private val walletListener = object : WalletConnectManager.Listener {
        override fun onSessionChanged(address: String?, chainId: String) {
            openTabs.values.forEach { it.bridge.onSessionChanged(address, chainId) }
        }

        override fun onWalletEvent(name: String, data: String) {
            openTabs.values.forEach { it.bridge.onWalletEvent(name, data) }
        }

        override fun onError(message: String) {
            openTabs[activeTab]?.bridge?.onError(message)
        }
    }
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var chromeViews: Map<BrowserControl, View>
    private var renderedLayout: BrowserLayout? = null
    private var renderedWidth = 0
    private var browserVisible = false
    private var pendingLaunch: Intent? = null
    private var pendingFarmSync = false
    private var onboardingDialog: AlertDialog? = null
    private var waitingForNotificationGrant = false
    private var resumedForLaunch = false
    private var stopObservingMode: (() -> Unit)? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        waitingForNotificationGrant = false
        continueLaunch()
    }
    private val notificationGrantSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        waitingForNotificationGrant = false
        if (Prefs.notificationOnboardingStep(this) == NotificationOnboardingStep.BATTERY.name &&
            TimerAlarm.canScheduleExact(this)
        ) {
            TimerAlarm.scheduleAll(this, forceRearm = true)
        }
        continueLaunch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        SystemBarInsets.apply(window, findViewById(R.id.mainRoot))

        bindViews()
        setUpChrome()
        findViewById<View>(R.id.mainRoot).addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) renderChrome()
        }
        pendingLaunch = intent.takeIf { savedInstanceState == null || savedInstanceState.getBoolean("pendingLaunch") }
        pendingFarmSync = savedInstanceState?.getBoolean("pendingFarmSync") ?: false
        waitingForNotificationGrant = savedInstanceState?.getBoolean("waitingForNotificationGrant") ?: false
        savedInstanceState?.getBundle("webTabs")?.let { states ->
            states.keySet().forEach { id -> states.getBundle(id)?.let { savedTabs[id] = it } }
        }
        activeTab = savedInstanceState?.getString("activeWebTab") ?: HOME_TAB
        setBrowserVisible(false)
        stopObservingMode = Prefs.observeBrowserMode(this) {
            if (Prefs.notificationsOnly(this)) disableBrowser()
        }

        PollScheduler.schedule(this)
        TimerAlarm.scheduleAll(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (openTabs[activeTab]?.view?.canGoBack() == true) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingLaunch = intent
    }

    override fun onPostResume() {
        super.onPostResume()
        resumedForLaunch = true
        SystemBarInsets.updateNavigation(window)
        continueLaunch()
    }

    override fun onPause() {
        resumedForLaunch = false
        super.onPause()
    }

    private fun continueLaunch() {
        if (isFinishing || isDestroyed || !resumedForLaunch) return
        if (waitingForNotificationGrant || onboardingDialog != null) return
        if (!Prefs.disclaimerAnswered(this)) {
            showOnboardingDialog(
                R.string.mode_disclaimer_title, R.string.mode_disclaimer_message,
                R.string.mode_notifications_only, R.string.mode_full_browser, DISCLAIMER_DELAY_MS,
            ) { choseRight ->
                Prefs.recordDisclaimerChoice(this, notificationsOnly = !choseRight)
                continueLaunch()
            }
            return
        }
        if (continueNotificationOnboarding()) return
        if (CrashReporting.available && Prefs.crashReportingChoice(this) == null) {
            showOnboardingDialog(
                R.string.crash_reporting_title, R.string.crash_reporting_message,
                R.string.crash_reporting_decline, R.string.crash_reporting_allow,
            ) { allowed ->
                CrashReporting.saveChoice(this, allowed)
                continueLaunch()
            }
            return
        }

        val launch = pendingLaunch
        pendingLaunch = null
        val openFarmSync = pendingFarmSync
        pendingFarmSync = false
        val shortcut = if (openFarmSync) SettingsActivity.farmSyncIntent(this) else when (launch?.action) {
            OPEN_OVERVIEW -> Intent(this, OverviewActivity::class.java)
            OPEN_TIMELINE -> Intent(this, TimelineActivity::class.java)
            OPEN_FARM_TASKS -> Intent(this, FarmTasksActivity::class.java)
            OPEN_SETTINGS -> Intent(this, SettingsActivity::class.java)
            else -> null
        }
        if (Prefs.notificationsOnly(this)) {
            disableBrowser()
            when {
                shortcut != null -> startActivity(shortcut)
                launch == null -> finish()
                launch.action == OPEN_NOTIFICATION && NotificationDestination.openSelectedApp(this) -> finish()
                else -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            return
        }

        setBrowserVisible(true)
        val restoredTab = HomeTabs.list(this).firstOrNull { it.id == activeTab }
        val url = launch?.data?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString()
        val mainTabLink = launch?.getBooleanExtra(EXTRA_OPEN_IN_MAIN_TAB, false) == true
        val startWithLink = openTabs.isEmpty() && savedTabs.isEmpty() && url != null
        if (openTabs.isEmpty()) {
            WalletConnectManager.listener = walletListener
            if (startWithLink) activateTab(HOME_TAB, checkNotNull(url))
            else activateTab(restoredTab?.id ?: HOME_TAB, restoredTab?.url ?: Prefs.homeUrl(this))
        }
        if (url != null && !startWithLink) {
            if (mainTabLink) {
                if (activeTab != HOME_TAB) activateTab(HOME_TAB, Prefs.homeUrl(this))
                openFarmVisit(url)
            } else webView.loadUrl(url)
        }
        handleWcLink(launch)
        syncBrowserSettings()
        if (shortcut != null) startActivity(shortcut)
    }

    private fun continueNotificationOnboarding(): Boolean {
        val savedStep = Prefs.notificationOnboardingStep(this)
        var step = NotificationOnboardingStep.entries.firstOrNull { it.name == savedStep }
            ?: NotificationOnboardingStep.NOTIFICATIONS
        fun advance(next: NotificationOnboardingStep) {
            step = next
            Prefs.setNotificationOnboardingStep(this, next.name)
        }
        if (step == NotificationOnboardingStep.NOTIFICATIONS) {
            advance(NotificationOnboardingStep.EXACT_ALARM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                waitingForNotificationGrant = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return true
            }
        }
        if (step == NotificationOnboardingStep.EXACT_ALARM) {
            advance(NotificationOnboardingStep.BATTERY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !TimerAlarm.canScheduleExact(this) &&
                launchNotificationGrant(NotificationGrants.exactAlarmIntent(this))
            ) return true
        }
        if (step == NotificationOnboardingStep.BATTERY) {
            advance(NotificationOnboardingStep.SETUP_REMINDER)
            if (!NotificationGrants.isIgnoringBattery(this) &&
                launchNotificationGrant(NotificationGrants.batteryExemptionIntent(this))
            ) return true
        }
        if (step == NotificationOnboardingStep.SETUP_REMINDER) {
            if (Prefs.hasApiKey(this) && Prefs.farmId(this).isNotBlank()) {
                advance(NotificationOnboardingStep.DONE)
            } else {
                showOnboardingDialog(
                    R.string.notification_setup_title, R.string.notification_setup_message,
                    R.string.notification_setup_ignore, R.string.notification_setup_settings,
                ) { choseRight ->
                    advance(NotificationOnboardingStep.DONE)
                    pendingFarmSync = choseRight
                    continueLaunch()
                }
                return true
            }
        }
        return false
    }

    private fun launchNotificationGrant(intent: Intent): Boolean {
        waitingForNotificationGrant = true
        return try {
            notificationGrantSettings.launch(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w("MainActivity", "Onboarding settings unavailable: ${intent.action}", e)
            waitingForNotificationGrant = false
            false
        } catch (e: SecurityException) {
            Log.w("MainActivity", "Onboarding settings not accessible: ${intent.action}", e)
            waitingForNotificationGrant = false
            false
        }
    }

    private fun showOnboardingDialog(
        @StringRes title: Int, @StringRes message: Int, @StringRes left: Int, @StringRes right: Int,
        delayMillis: Long = 0, onChoice: (choseRight: Boolean) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        val background = applicationContext.getColor(R.color.sfl_page)
        val orange = applicationContext.getColor(R.color.sfl_accent)
        val body = layoutInflater.inflate(R.layout.dialog_onboarding, null)
        body.findViewById<TextView>(R.id.onboardingTitle).apply {
            setText(title)
            setTextColor(orange)
        }
        body.findViewById<TextView>(R.id.onboardingMessage).apply {
            setText(message)
            setTextColor(orange)
        }
        val leftButton = body.findViewById<MaterialButton>(R.id.onboardingLeftChoice)
        val rightButton = body.findViewById<MaterialButton>(R.id.onboardingRightChoice)
        val actionColors = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(0xffa49687.toInt(), orange),
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setBackground(rounded(this, background, radius = 20))
            .setView(body)
            .setCancelable(false)
            .create()
        var choice: Boolean? = null
        fun bindChoice(button: MaterialButton, @StringRes label: Int, isRight: Boolean) {
            button.setText(label)
            button.setTextColor(actionColors)
            button.strokeColor = actionColors
            button.rippleColor = ColorStateList.valueOf((orange and 0x00ffffff) or 0x22000000)
            button.isEnabled = delayMillis == 0L
            button.setOnClickListener {
                choice = isRight
                dialog.dismiss()
            }
        }
        bindChoice(leftButton, left, isRight = false)
        bindChoice(rightButton, right, isRight = true)
        onboardingDialog = dialog
        val unlock = Runnable {
            leftButton.isEnabled = true
            rightButton.isEnabled = true
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(unlock)
            onboardingDialog = null
            choice?.let(onChoice)
        }
        dialog.show()
        if (delayMillis > 0) handler.postDelayed(unlock, delayMillis)
    }

    private fun openFarmVisit(url: String) {
        val view = webView
        if (view.url?.substringBefore('#') != url.substringBefore('#')) {
            view.loadUrl(url)
            return
        }
        // The game can keep showing the current farm on a fragment-only navigation.
        // Reload the document after changing the route, as a manual refresh would.
        view.evaluateJavascript("""
            (() => {
                const target = new URL(${JSONObject.quote(url)});
                if (location.href.split('#')[0] !== target.href.split('#')[0]) return false;
                location.hash = target.hash;
                location.reload();
                return true;
            })()
        """.trimIndent()) { reloaded -> if (reloaded != "true") view.loadUrl(url) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("pendingLaunch", pendingLaunch != null)
        outState.putBoolean("pendingFarmSync", pendingFarmSync)
        outState.putBoolean("waitingForNotificationGrant", waitingForNotificationGrant)
        outState.putString("activeWebTab", activeTab)
        outState.putBundle("webTabs", Bundle().apply {
            savedTabs.forEach { (id, state) -> putBundle(id, state) }
            openTabs.forEach { (id, tab) ->
                val state = Bundle()
                if (tab.view.saveState(state) != null) {
                    state.putString(TAB_HOME_URL, tab.homeUrl)
                    putBundle(id, state)
                }
            }
        })
    }

    private fun setBrowserVisible(visible: Boolean) {
        browserVisible = visible
        webTabs.visibility = if (visible) View.VISIBLE else View.GONE
        renderChrome()
        if (!visible) progress.visibility = View.GONE
    }

    private fun renderChrome() {
        val root = findViewById<View>(R.id.mainRoot)
        val top = findViewById<android.widget.LinearLayout>(R.id.browserControls)
        val bottom = findViewById<android.widget.LinearLayout>(R.id.bottomBrowserControls)
        if (!browserVisible || Prefs.hideBrowserControls(this)) {
            top.visibility = View.GONE
            bottom.visibility = View.GONE
            return
        }
        val layout = BrowserLayout.read(this)
        val width = (root.width - root.paddingLeft - root.paddingRight).takeIf { it > 0 }
            ?: dp(resources.configuration.screenWidthDp)
        if (renderedLayout != layout || renderedWidth != width) {
            top.removeAllViews()
            bottom.removeAllViews()
            renderBrowserChrome(top, false, layout, width, chromeViews)
            renderBrowserChrome(bottom, true, layout, width, chromeViews)
            renderedLayout = layout
            renderedWidth = width
        } else {
            top.visibility = if (top.childCount == 0) View.GONE else View.VISIBLE
            bottom.visibility = if (bottom.childCount == 0) View.GONE else View.VISIBLE
        }
    }

    private fun syncBrowserSettings() {
        updateProgress(webView.progress)
        NativeFont.apply(findViewById(R.id.mainRoot))
        val tabs = HomeTabs.list(this).associateBy { it.id }
        val configured = tabs.keys + HOME_TAB
        if (activeTab !in configured) activateTab(HOME_TAB, Prefs.homeUrl(this))
        openTabs.keys.filterNot { it in configured }.forEach { id ->
            openTabs.remove(id)?.let(::destroyTab)
        }
        savedTabs.keys.retainAll(configured)
        tabs.forEach { (id, tab) ->
            if (openTabs[id]?.let { it.homeUrl != tab.url } == true) {
                openTabs.remove(id)?.let(::destroyTab)
                if (activeTab == id) activateTab(id, tab.url)
            }
        }
    }

    override fun onStop() {
        captureActiveTab()
        super.onStop()
    }

    override fun onDestroy() {
        stopObservingMode?.invoke()
        onboardingDialog?.dismiss()
        destroyBrowserTabs()
        super.onDestroy()
    }

    private fun disableBrowser() {
        setBrowserVisible(false)
        destroyBrowserTabs()
        savedTabs.clear()
        activeTab = HOME_TAB
    }

    private fun destroyBrowserTabs() {
        if (WalletConnectManager.listener === walletListener) WalletConnectManager.listener = null
        openTabs.values.forEach(::destroyTab)
        openTabs.clear()
    }

    private fun destroyTab(tab: BrowserTab) {
        tab.bridge.detach()
        webTabs.removeView(tab.view)
        tab.view.stopLoading()
        tab.view.destroy()
    }

    // ------------------------------------------------------------- views

    private fun bindViews() {
        webTabs = findViewById(R.id.webTabs)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
    }

    private fun activateTab(id: String, homeUrl: String) {
        hideKeyboard()
        val previous = openTabs[activeTab]?.view
        if (activeTab != id) captureActiveTab()
        activeTab = id
        val existing = openTabs[id]
        if (existing == null) {
            val view = WebView(this)
            val bridge = EthereumBridge(view)
            openTabs[id] = BrowserTab(view, bridge, homeUrl)
            setUpWebView(view, bridge)
            wireWallet(view, bridge)
            bridge.attach()
            webTabs.addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            val state = savedTabs.remove(id)?.takeIf { id == HOME_TAB || it.getString(TAB_HOME_URL) == homeUrl }
            if (state == null || view.restoreState(state) == null) view.loadUrl(homeUrl)
        }
        if (previous != null && previous !== webView) previous.visibility = View.GONE
        webView.visibility = View.VISIBLE
        showUrl(webView.url ?: homeUrl)
        updateProgress(webView.progress)
        syncNav()
    }

    private fun updateProgress(value: Int) {
        progress.progress = value
        progress.visibility = if (value >= 100 || Prefs.hideBrowserControls(this)) View.GONE else View.VISIBLE
    }

    private fun captureActiveTab() {
        val browser = openTabs[activeTab] ?: return
        val tab = if (activeTab == HOME_TAB) mainHomeTab()
            else HomeTabs.list(this).firstOrNull { it.id == activeTab && it.url == browser.homeUrl } ?: return
        TabPreviews.save(browser.view, tab)
    }

    private fun mainHomeTab() = HomeTab(HOME_TAB, getString(R.string.browser_main_tab), Prefs.homeUrl(this))

    private fun setUpWebView(webView: WebView, bridge: EthereumBridge) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // The game's music and video are user-initiated, not auto-play bait.
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view === openTabs[activeTab]?.view) updateProgress(newProgress)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Before the page's own scripts run, so dApps see the provider
                // the moment they look for it; the finished pass is the net.
                bridge.installShim()
                if (view === openTabs[activeTab]?.view) {
                    showUrl(url)
                    syncNav()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                bridge.installShim()
                if (view === openTabs[activeTab]?.view) {
                    showUrl(url)
                    syncNav()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: return false
                if (scheme == "http" || scheme == "https") return false // load in place

                // Anything else (mailto:, intent:, a wallet's own scheme) is not
                // ours to render — hand it to whatever can open it.
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: Exception) {
                    true
                }
            }
        }
    }

    private fun setUpChrome() {
        chromeViews = mapOf(
            BrowserControl.BACK to btnBack,
            BrowserControl.FORWARD to btnForward,
            BrowserControl.ADDRESS to urlBar,
            BrowserControl.TABS to findViewById<ImageButton>(R.id.btnTabs),
            BrowserControl.OPTIONS to findViewById<ImageButton>(R.id.btnOptions),
            BrowserControl.REFRESH to shortcut(BrowserControl.REFRESH, R.id.menu_refresh),
            BrowserControl.OVERVIEW to shortcut(BrowserControl.OVERVIEW, R.id.menu_overview),
            BrowserControl.TIMELINE to shortcut(BrowserControl.TIMELINE, R.id.menu_timeline),
            BrowserControl.SETTINGS to shortcut(BrowserControl.SETTINGS, R.id.menu_settings),
        )
        btnBack.setOnClickListener { webView.goBack() }
        btnForward.setOnClickListener { webView.goForward() }
        findViewById<ImageButton>(R.id.btnTabs).setOnClickListener {
            hideKeyboard()
            captureActiveTab()
            TabSwitcher(this, listOf(mainHomeTab()) + HomeTabs.list(this), activeTab) {
                activateTab(it.id, it.url)
            }.show()
        }
        findViewById<ImageButton>(R.id.btnOptions).setOnClickListener { button ->
            PopupMenu(this, button).apply {
                menuInflater.inflate(R.menu.browser_menu, menu)
                for (i in 0 until menu.size()) {
                    val item = menu.getItem(i)
                    item.title = SpannableString(item.title ?: "").apply {
                        setSpan(ForegroundColorSpan(getColor(R.color.sfl_text_white)), 0, length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(NativeFont.percent(this@MainActivity) / 100f),
                            0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                setOnMenuItemClickListener { onOptionsItemSelected(it) }
                show()
            }
        }

        urlBar.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                (event != null &&
                    event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN)
            if (go) {
                loadFromUrlBar()
                true
            } else {
                false
            }
        }
        urlBar.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) urlBar.selectAll() }
    }

    private fun shortcut(control: BrowserControl, menuId: Int): ImageButton =
        browserButton(this, control, ControlSize.SMALL).apply {
            setOnClickListener { openBrowserAction(menuId) }
        }

    // ------------------------------------------------------------- loading

    private fun loadFromUrlBar() {
        val raw = urlBar.text.toString().trim()
        if (raw.isEmpty()) return
        val url = when {
            raw.contains("://") -> raw
            // Something with spaces (or no dot at all) is a search, not a host.
            raw.contains(" ") || !raw.contains(".") ->
                "https://www.google.com/search?q=" + URLEncoder.encode(raw, "UTF-8")

            else -> "https://$raw"
        }
        hideKeyboard()
        webView.loadUrl(url)
    }

    /** Only rewrite the bar when the user is not typing in it. */
    private fun showUrl(url: String?) {
        if (url.isNullOrEmpty() || urlBar.hasFocus()) return
        urlBar.setText(url)
        urlBar.setSelection(urlBar.text.length)
    }

    private fun syncNav() {
        val back = webView.canGoBack()
        btnBack.isEnabled = back
        btnBack.alpha = if (back) 1f else 0.35f
        val forward = webView.canGoForward()
        btnForward.isEnabled = forward
        btnForward.alpha = if (forward) 1f else 0.35f
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    // --------------------------------------------------------------- menu

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        openBrowserAction(item.itemId) || super.onOptionsItemSelected(item)

    private fun openBrowserAction(id: Int): Boolean = when (id) {
        R.id.menu_refresh -> {
            hideKeyboard()
            webView.reload()
            true
        }

        R.id.menu_overview -> {
            startActivity(Intent(this, OverviewActivity::class.java))
            true
        }

        R.id.menu_timeline -> {
            startActivity(Intent(this, TimelineActivity::class.java))
            true
        }

        R.id.menu_farm_tasks -> {
            startActivity(Intent(this, FarmTasksActivity::class.java))
            true
        }

        R.id.menu_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }

        else -> false
    }

    // -------------------------------------------------------------- wallet

    private fun wireWallet(view: WebView, bridge: EthereumBridge) {
        bridge.onConnectRequested = {
            // The page asked for accounts and no session exists yet.
            runOnUiThread { if (view === openTabs[activeTab]?.view) startConnect() }
        }
        bridge.onWalletError = { message ->
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
    }

    /** Start a WalletConnect pairing when a website asks the provider for accounts. */
    private fun startConnect() {
        if (WalletConnectManager.address != null) return
        WalletConnectManager.init(this) { ok ->
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this, R.string.wallet_init_failed, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                WalletConnectManager.createConnect(
                    onUri = { result ->
                        runOnUiThread {
                            val uri = result.getOrElse {
                                onConnectResult(result)
                                return@runOnUiThread
                            }
                            openWallet(uri)
                        }
                    },
                    onResult = { result -> runOnUiThread { onConnectResult(result) } },
                )
            }
        }
    }

    /** A wallet deep-linked back to us with a `wc:` URI to pair on. */
    private fun handleWcLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (!data.toString().startsWith("sflbrowser://")) return

        val wcUri = data.getQueryParameter("uri")
            ?: data.toString().substringAfter("wc:", "").takeIf { it.isNotBlank() }?.let { "wc:$it" }
            ?: return

        WalletConnectManager.init(this) { ok ->
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this, R.string.wallet_init_failed, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                WalletConnectManager.connect(wcUri) { result -> runOnUiThread { onConnectResult(result) } }
            }
        }
    }

    private fun onConnectResult(result: Result<String>) {
        if (result.isFailure) {
            val message = result.exceptionOrNull()?.message.orEmpty()
            val rejected = message.contains("reject", ignoreCase = true) ||
                message.contains("closed", ignoreCase = true) ||
                message.contains("expired", ignoreCase = true)
            if (rejected || message.isEmpty()) {
                Toast.makeText(this, R.string.wallet_rejected, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        // The dApp's pending eth_requestAccounts promise resolves on approval.
    }

    private fun openWallet(uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.wallet_no_app, Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------ plumbing

    companion object {
        private const val HOME_TAB = "main-home"
        private const val TAB_HOME_URL = "configuredTabUrl"
        private const val OPEN_SETTINGS = "com.sfl.browser.OPEN_SETTINGS"
        private const val OPEN_OVERVIEW = "com.sfl.browser.OPEN_OVERVIEW"
        private const val OPEN_TIMELINE = "com.sfl.browser.OPEN_TIMELINE"
        private const val OPEN_FARM_TASKS = "com.sfl.browser.OPEN_FARM_TASKS"
        const val OPEN_NOTIFICATION = "com.sfl.browser.OPEN_NOTIFICATION"
        private const val EXTRA_OPEN_IN_MAIN_TAB = "com.sfl.browser.OPEN_IN_MAIN_TAB"
        private const val DISCLAIMER_DELAY_MS = 5_000L

        /** In-app navigation to [url] on the main Home tab, e.g. from the info screens. */
        fun viewInMainTab(context: Context, url: String): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(url))
                .putExtra(EXTRA_OPEN_IN_MAIN_TAB, true)
    }
}
