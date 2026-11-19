package com.sfl.browser.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.Filter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.sfl.browser.R
import com.sfl.browser.data.HomeTabs
import com.sfl.browser.data.HomeTab
import com.sfl.browser.data.ItemIcons
import com.sfl.browser.data.Prefs
import com.sfl.browser.data.PriceAlert
import com.sfl.browser.data.SecureStore
import com.sfl.browser.data.TimerCategory
import com.sfl.browser.diagnostics.CrashReporting
import com.sfl.browser.notify.PollFeedback
import com.sfl.browser.notify.PollScheduler
import com.sfl.browser.notify.TimerAlarm
import java.util.UUID

/**
 * Every setting the background service reads, plus the two system grants it
 * leans on (exact alarms, battery exemption) and a "run a check now" button so
 * a fresh install can prove the API key works without waiting a poll cycle.
 */
class SettingsActivity : PaletteActivity() {

    private var exactAlarmGrantOnResume: Boolean? = null
    private var farmSettingsChanged = false
    private val textCommitHandler = Handler(Looper.getMainLooper())
    private val pendingTextCommits = LinkedHashMap<EditText, Runnable>()

    private lateinit var apiKey: EditText
    private lateinit var farmId: EditText
    private lateinit var homeUrl: EditText
    private lateinit var pollValue: EditText
    private lateinit var pollUnit: Spinner
    private lateinit var wifiOnly: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var batterySaving: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var offsetValue: EditText
    private lateinit var offsetUnit: Spinner
    private lateinit var exactAlarmStatus: TextView
    private lateinit var btnExactAlarm: Button
    private lateinit var ignoreBattery: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var groupWindow: EditText
    private lateinit var groupWindowUnit: Spinner
    private lateinit var timerCategoryToggles: LinearLayout
    private lateinit var cookingMode: Spinner
    private lateinit var craftingBoxMode: Spinner
    private lateinit var enableEvents: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var leadValue: EditText
    private lateinit var leadUnit: Spinner
    private lateinit var enableSales: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var enablePrices: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var priceAlertList: LinearLayout
    private lateinit var priceAlertEmpty: TextView
    private lateinit var testNow: Button
    private lateinit var checkStatus: TextView
    private var checkDots = 1
    private val animateCheckButton = object : Runnable {
        override fun run() {
            testNow.text = getString(R.string.check_running_button, ".".repeat(checkDots))
            checkDots = checkDots % 3 + 1
            testNow.postDelayed(this, 450L)
        }
    }
    private val lastCheckPrefs by lazy { getSharedPreferences("manual_check", Context.MODE_PRIVATE) }
    private val swipeBounds = android.graphics.Rect()
    private var swipeOrigin: Pair<Float, Float>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        SystemBarInsets.apply(window, findViewById(R.id.settingsRoot))

        findViewById<MaterialToolbar>(R.id.toolbar).let { toolbar ->
            toolbar.setNavigationOnClickListener { finish() }
            toolbar.post { NativeFont.apply(toolbar) }
        }
        bindViews()
        load()
        buildTimerCategoryToggles()
        renderAlerts()
        renderHomeTabs()

        testNow = findViewById(R.id.btnTestNow)
        checkStatus = findViewById(R.id.checkStatus)
        testNow.setOnClickListener { runNow() }
        lastCheckPrefs.getString("id", null)?.let { savedId ->
            runCatching { UUID.fromString(savedId) }.getOrNull()?.let(::observeCheck)
        }
        findViewById<Button>(R.id.btnNativeFontSize).setOnClickListener { showNativeFontDialog() }
        findViewById<Button>(R.id.btnBrowserLayout).setOnClickListener {
            startActivity(Intent(this, BrowserLayoutActivity::class.java))
        }
        findViewById<Button>(R.id.btnColorTheme).setOnClickListener {
            startActivity(Intent(this, ThemeEditorActivity::class.java))
        }
        findViewById<Button>(R.id.btnAddHomeTab).setOnClickListener { showHomeTabDialog() }
        bindBrowserMode()
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.hideBrowserControls).apply {
            isChecked = Prefs.hideBrowserControls(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setHideBrowserControls(this@SettingsActivity, checked)
            }
        }
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.hideAndroidNavigation).apply {
            isChecked = Prefs.hideAndroidNavigation(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setHideAndroidNavigation(this@SettingsActivity, checked)
                SystemBarInsets.updateNavigation(window)
            }
        }
        findViewById<Button>(R.id.btnAddPriceAlert).setOnClickListener { showAddAlertDialog() }
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.batchedPriceChecks).apply {
            isChecked = Prefs.batchedPriceChecks(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setBatchedPriceChecks(this@SettingsActivity, checked)
            }
        }
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.cropMachineAllDone).apply {
            isChecked = Prefs.cropMachineAllDone(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setCropMachineAllDone(this@SettingsActivity, checked)
                TimerAlarm.scheduleAll(this@SettingsActivity)
            }
        }
        btnExactAlarm.setOnClickListener { requestExactAlarm() }
        ignoreBattery.setOnClickListener { requestBatteryExemption() }
        bindImmediateListeners()
        bindSettingsTabs(savedInstanceState?.getInt("settingsTab") ?: if (intent.action == OPEN_FARM_SYNC) FARM_SYNC_TAB else 0)
        if (CrashReporting.available) {
            findViewById<View>(R.id.crashReportingControls).visibility = View.VISIBLE
            findViewById<MaterialSwitch>(R.id.shareCrashReports).apply {
                isChecked = Prefs.crashReportingChoice(this@SettingsActivity) == true
                setOnCheckedChangeListener { _, checked ->
                    CrashReporting.saveChoice(this@SettingsActivity, checked)
                }
            }
        }
        findViewById<Button>(R.id.btnAboutDev).setOnClickListener {
            startActivity(SettingsInfoActivity.intent(this, SettingsInfoActivity.PAGE_ABOUT_DEV))
        }
        findViewById<Button>(R.id.btnCredits).setOnClickListener {
            startActivity(SettingsInfoActivity.intent(this, SettingsInfoActivity.PAGE_CREDITS))
        }
        findViewById<Button>(R.id.btnTutorial).setOnClickListener {
            startActivity(SettingsInfoActivity.intent(this, SettingsInfoActivity.PAGE_TUTORIAL))
        }
        findViewById<Button>(R.id.btnPrivacy).setOnClickListener {
            openExternalLink(SettingsInfoActivity.PRIVACY_PAGE)
        }
        findViewById<Button>(R.id.btnGithub).setOnClickListener {
            openExternalLink(SettingsInfoActivity.GITHUB_REPO)
        }
        findViewById<Button>(R.id.btnContact).setOnClickListener {
            openExternalLink("mailto:" + getString(R.string.settings_contact_email))
        }
        applyNativeFont()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("settingsTab", findViewById<TabLayout>(R.id.settingsTabs).selectedTabPosition)
        super.onSaveInstanceState(outState)
    }

    private fun bindBrowserMode() {
        val button = findViewById<Button>(R.id.btnNotificationApp)
        fun update() {
            val selected = Prefs.notificationApp(this)
            val label = selected?.let { name ->
                NotificationDestination.installedApps(this).firstOrNull { it.packageName == name }?.label
                    ?: getString(R.string.notification_app_unavailable, name)
            } ?: getString(R.string.settings_title)
            button.text = getString(R.string.notification_app_current, label)
            button.visibility = if (Prefs.notificationsOnly(this)) View.VISIBLE else View.GONE
        }
        button.setOnClickListener {
            val apps = NotificationDestination.installedApps(this)
            val duplicateLabels = apps.groupingBy { it.label }.eachCount()
            val labels = listOf(getString(R.string.settings_title)) + apps.map {
                if (duplicateLabels.getValue(it.label) == 1) it.label else "${it.label} (${it.packageName})"
            }
            val selected = Prefs.notificationApp(this)
            val checked = if (selected == null) 0 else
                apps.indexOfFirst { it.packageName == selected }.let { if (it < 0) -1 else it + 1 }
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.notification_app_title)
                .setSingleChoiceItems(labels.toTypedArray(), checked) { picker, index ->
                    Prefs.setNotificationApp(this, apps.getOrNull(index - 1)?.packageName)
                    update()
                    picker.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            dialog.window?.decorView?.let(NativeFont::apply)
        }
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.notificationsOnly).apply {
            isChecked = Prefs.notificationsOnly(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setNotificationsOnly(this@SettingsActivity, checked)
                update()
            }
        }
        update()
    }

    private fun bindSettingsTabs(selected: Int) {
        val tabs = findViewById<TabLayout>(R.id.settingsTabs)
        val scroll = findViewById<ScrollView>(R.id.settingsScroll)
        val pages = listOf(
            R.string.settings_tab_browser to findViewById<View>(R.id.settingsBrowserPage),
            R.string.settings_tab_farm to findViewById<View>(R.id.settingsFarmPage),
            R.string.settings_tab_notifications to findViewById<View>(R.id.settingsNotificationsPage),
            R.string.section_price_alerts to findViewById<View>(R.id.settingsPricesPage),
            R.string.settings_tab_about to findViewById<View>(R.id.settingsAboutPage),
        )
        fun showPage(position: Int) {
            pages.forEachIndexed { index, (_, page) ->
                page.visibility = if (index == position) View.VISIBLE else View.GONE
            }
        }
        val initial = selected.coerceIn(pages.indices)
        pages.forEachIndexed { index, (title, _) ->
            val label = layoutInflater.inflate(R.layout.settings_tab_label, tabs, false) as TextView
            label.setText(title)
            tabs.addTab(tabs.newTab().setText(title).setCustomView(label), index == initial)
        }

        showPage(initial)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                (currentFocus as? EditText)?.let { field ->
                    getSystemService(InputMethodManager::class.java)
                        .hideSoftInputFromWindow(field.windowToken, 0)
                    field.clearFocus()
                }
                commitPendingTextSettings()
                showPage(tab.position)
                scroll.scrollTo(0, 0)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val scroll = findViewById<ScrollView>(R.id.settingsScroll)
                swipeOrigin = if (scroll.getGlobalVisibleRect(swipeBounds) &&
                    swipeBounds.contains(event.rawX.toInt(), event.rawY.toInt())
                ) event.rawX to event.rawY else null
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> swipeOrigin = null
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val (x, y) = swipeOrigin ?: return super.dispatchTouchEvent(event)
                val dx = event.rawX - x
                val dy = abs(event.rawY - y)
                val threshold = 64 * resources.displayMetrics.density
                if (dy >= threshold && dy >= abs(dx)) swipeOrigin = null
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    swipeOrigin = null
                    if (abs(dx) >= threshold && abs(dx) > 2 * dy) {
                        val tabs = findViewById<TabLayout>(R.id.settingsTabs)
                        val next = tabs.getTabAt(tabs.selectedTabPosition + if (dx < 0) 1 else -1)
                        if (next != null) {
                            val cancel = MotionEvent.obtain(event)
                            cancel.action = MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()
                            next.select()
                            return true
                        }
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onStart() {
        super.onStart()
        if (!testNow.isEnabled) {
            testNow.removeCallbacks(animateCheckButton)
            animateCheckButton.run()
        }
    }

    override fun onStop() {
        testNow.removeCallbacks(animateCheckButton)
        super.onStop()
    }

    override fun onPause() {
        commitPendingTextSettings()
        if (farmSettingsChanged) {
            runNowIfConfigured()
            farmSettingsChanged = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        pendingTextCommits.values.forEach(textCommitHandler::removeCallbacks)
        pendingTextCommits.clear()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val exactAlarmGranted = TimerAlarm.canScheduleExact(this)
        if (exactAlarmGrantOnResume != null && exactAlarmGrantOnResume != exactAlarmGranted) {
            TimerAlarm.scheduleAll(this, forceRearm = true)
        }
        exactAlarmGrantOnResume = exactAlarmGranted
        refreshGrants()
    }

    // ------------------------------------------------------------- wiring

    private fun bindViews() {
        apiKey = findViewById(R.id.apiKey)
        farmId = findViewById(R.id.farmId)
        homeUrl = findViewById(R.id.homeUrl)
        pollValue = findViewById(R.id.pollValue)
        pollUnit = findViewById(R.id.pollUnit)
        wifiOnly = findViewById(R.id.wifiOnly)
        batterySaving = findViewById(R.id.batterySaving)
        offsetValue = findViewById(R.id.offsetValue)
        offsetUnit = findViewById(R.id.offsetUnit)
        exactAlarmStatus = findViewById(R.id.exactAlarmStatus)
        btnExactAlarm = findViewById(R.id.btnExactAlarm)
        ignoreBattery = findViewById(R.id.ignoreBattery)
        groupWindow = findViewById(R.id.groupWindow)
        groupWindowUnit = findViewById(R.id.groupWindowUnit)
        timerCategoryToggles = findViewById(R.id.timerCategoryToggles)
        cookingMode = findViewById(R.id.cookingMode)
        craftingBoxMode = findViewById(R.id.craftingBoxMode)
        enableEvents = findViewById(R.id.enableEvents)
        leadValue = findViewById(R.id.leadValue)
        leadUnit = findViewById(R.id.leadUnit)
        enableSales = findViewById(R.id.enableSales)
        enablePrices = findViewById(R.id.enablePrices)
        priceAlertList = findViewById(R.id.priceAlertList)
        priceAlertEmpty = findViewById(R.id.priceAlertEmpty)
        listOf(pollUnit, offsetUnit, groupWindowUnit, leadUnit).forEach {
            it.adapter = nativeSpinnerAdapter(R.array.time_units)
        }
        cookingMode.adapter = nativeSpinnerAdapter(R.array.cooking_modes)
        craftingBoxMode.adapter = nativeSpinnerAdapter(R.array.crafting_box_modes)
    }

    private fun load() {
        // Shown so the user can tell which key is in use; it only ever leaves
        // this device through the encrypted store.
        apiKey.setText(Prefs.apiKey(this).orEmpty())
        farmId.setText(Prefs.farmId(this))
        homeUrl.setText(Prefs.homeUrl(this))

        pollValue.setText(Prefs.pollValue(this).toString())
        pollUnit.setSelection(Prefs.pollUnit(this))
        wifiOnly.isChecked = Prefs.wifiOnly(this)

        batterySaving.isChecked = Prefs.batterySaving(this)
        offsetValue.setText(Prefs.offsetValue(this).toString())
        offsetUnit.setSelection(Prefs.offsetUnit(this))

        groupWindow.setText(Prefs.groupValue(this).toString())
        groupWindowUnit.setSelection(Prefs.groupUnit(this))

        cookingMode.setSelection(Prefs.cookingMode(this))
        craftingBoxMode.setSelection(if (Prefs.craftingBoxAllDone(this)) 1 else 0)
        enableEvents.isChecked = Prefs.enableEvents(this)
        leadValue.setText(Prefs.leadValue(this).toString())
        leadUnit.setSelection(Prefs.leadUnit(this))
        enableSales.isChecked = Prefs.enableSales(this)
        enablePrices.isChecked = Prefs.enablePrices(this)

        refreshGrants()
    }

    private fun refreshGrants() {
        val exact = TimerAlarm.canScheduleExact(this)
        exactAlarmStatus.setText(if (exact) R.string.exact_alarm_granted else R.string.exact_alarm_missing)
        btnExactAlarm.isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        btnExactAlarm.setText(if (exact && btnExactAlarm.isEnabled) R.string.btn_manage_exact_alarm else R.string.btn_grant_exact_alarm)
        ignoreBattery.isChecked = NotificationGrants.isIgnoringBattery(this)
    }

    // ------------------------------------------------------ immediate saves

    private fun buildTimerCategoryToggles() {
        val options = listOf(
            TimerCategoryOption(TimerCategory.CROPS, R.string.category_crops),
            TimerCategoryOption(TimerCategory.FRUIT, R.string.category_fruit),
            TimerCategoryOption(TimerCategory.TREES, R.string.category_trees),
            TimerCategoryOption(TimerCategory.RESOURCES, R.string.category_resources),
            TimerCategoryOption(TimerCategory.OIL, R.string.category_oil),
            TimerCategoryOption(TimerCategory.BUILDINGS, R.string.category_buildings),
            TimerCategoryOption(TimerCategory.CRAFTING_BOX, R.string.category_crafting_box),
            TimerCategoryOption(TimerCategory.GREENHOUSE, R.string.category_greenhouse),
            TimerCategoryOption(TimerCategory.FLOWERS, R.string.category_flowers),
            TimerCategoryOption(TimerCategory.CRAB_TRAPS, R.string.category_crab_traps),
            TimerCategoryOption(TimerCategory.LAVA_PITS, R.string.category_lava_pits),
            TimerCategoryOption(TimerCategory.EXPANSION, R.string.category_expansion),
            TimerCategoryOption(TimerCategory.COLLECTIBLES, R.string.category_collectibles),
            TimerCategoryOption(TimerCategory.CAVE, R.string.category_cave),
            TimerCategoryOption(TimerCategory.CROP_MACHINE, R.string.cat_crop_machine),
            TimerCategoryOption(TimerCategory.CHICKEN_FEEDING, R.string.category_chicken_feeding),
            TimerCategoryOption(TimerCategory.COW_FEEDING, R.string.category_cow_feeding),
            TimerCategoryOption(TimerCategory.SHEEP_FEEDING, R.string.category_sheep_feeding),
            TimerCategoryOption(TimerCategory.PIG_FEEDING, R.string.category_pig_feeding),
            TimerCategoryOption(TimerCategory.ANIMAL_LOVE, R.string.category_animal_love),
        )

        options.forEach { option ->
            val toggle = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setText(option.label)
                setTextColor(getColor(R.color.sfl_text_black))
                isChecked = Prefs.timerCategoryEnabled(this@SettingsActivity, option.category)
            }
            toggle.setOnCheckedChangeListener { _, enabled ->
                if (Prefs.timerCategoryEnabled(this, option.category) != enabled) {
                    Prefs.setTimerCategoryEnabled(this, option.category, enabled)
                    TimerAlarm.scheduleAll(this)
                    runNowIfConfigured()
                }
            }
            timerCategoryToggles.addView(toggle)
        }
    }

    private fun bindImmediateListeners() {
        watchText(apiKey) {
            val value = apiKey.text.toString().trim()
            if (value.isNotEmpty() && value != Prefs.apiKey(this).orEmpty()) {
                SecureStore.put(this, value)
                farmSettingsChanged = true
            }
        }
        watchText(farmId) {
            val value = farmId.text.toString().trim()
            if (value != Prefs.farmId(this)) {
                Prefs.setFarmId(this, value)
                farmSettingsChanged = true
            }
        }
        watchText(homeUrl) {
            val value = homeUrl.text.toString()
            if (value != Prefs.homeUrl(this)) Prefs.setHomeUrl(this, value)
        }

        watchText(pollValue) { commitPollSettings() }
        pollUnit.onItemSelectedListener = selectionListener { commitPollSettings() }
        wifiOnly.setOnCheckedChangeListener { _, enabled ->
            if (enabled != Prefs.wifiOnly(this)) {
                Prefs.setWifiOnly(this, enabled)
                PollScheduler.schedule(this)
            }
        }

        batterySaving.setOnCheckedChangeListener { _, enabled ->
            if (enabled != Prefs.batterySaving(this)) {
                Prefs.setBatterySaving(this, enabled)
                TimerAlarm.scheduleAll(this)
                runNowIfConfigured()
            }
        }
        watchText(offsetValue) { commitOffsetSettings() }
        offsetUnit.onItemSelectedListener = selectionListener { commitOffsetSettings() }

        watchText(groupWindow) { commitGroupWindowSettings() }
        groupWindowUnit.onItemSelectedListener = selectionListener { commitGroupWindowSettings() }

        cookingMode.onItemSelectedListener = selectionListener {
            val mode = cookingMode.selectedItemPosition
            if (mode != Prefs.cookingMode(this)) {
                Prefs.setCookingMode(this, mode)
                runNowIfConfigured()
            }
            craftingBoxMode.onItemSelectedListener = selectionListener {
                val allDone = craftingBoxMode.selectedItemPosition == 1
                if (allDone != Prefs.craftingBoxAllDone(this)) {
                    Prefs.setCraftingBoxAllDone(this, allDone)
                    TimerAlarm.scheduleAll(this)
                }
            }
        }
        enableEvents.setOnCheckedChangeListener { _, enabled ->
            if (enabled != Prefs.enableEvents(this)) {
                Prefs.setEnableEvents(this, enabled)
                runNowIfConfigured()
            }
        }
        watchText(leadValue) { commitLeadSettings() }
        leadUnit.onItemSelectedListener = selectionListener { commitLeadSettings() }
        enableSales.setOnCheckedChangeListener { _, enabled ->
            if (enabled != Prefs.enableSales(this)) {
                Prefs.setEnableSales(this, enabled)
                runNowIfConfigured()
            }
        }
        enablePrices.setOnCheckedChangeListener { _, enabled ->
            if (enabled != Prefs.enablePrices(this)) {
                Prefs.setEnablePrices(this, enabled)
                runNowIfConfigured()
            }
        }
    }

    private fun watchText(field: EditText, commit: () -> Unit) {
        val pending = Runnable(commit)
        pendingTextCommits[field] = pending
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                textCommitHandler.removeCallbacks(pending)
                textCommitHandler.postDelayed(pending, TEXT_COMMIT_DELAY_MS)
            }
        })
        field.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitTextSetting(field)
        }
    }

    private fun commitTextSetting(field: EditText) {
        pendingTextCommits[field]?.let {
            textCommitHandler.removeCallbacks(it)
            it.run()
        }
    }

    private fun commitPendingTextSettings() {
        pendingTextCommits.keys.toList().forEach(::commitTextSetting)
    }

    private fun commitPollSettings() {
        val value = pollValue.text.toString().trim().toIntOrNull()
        if (value == null || value < 1) {
            pollValue.error = getString(R.string.err_number)
            return
        }
        pollValue.error = null
        val unit = pollUnit.selectedItemPosition
        if (value != Prefs.pollValue(this) || unit != Prefs.pollUnit(this)) {
            Prefs.setPoll(this, value, unit)
            PollScheduler.schedule(this)
        }
    }

    private fun commitOffsetSettings() {
        val value = offsetValue.text.toString().trim().toIntOrNull()
        if (value == null) {
            offsetValue.error = getString(R.string.err_number)
            return
        }
        offsetValue.error = null
        val unit = offsetUnit.selectedItemPosition
        if (value != Prefs.offsetValue(this) || unit != Prefs.offsetUnit(this)) {
            Prefs.setOffset(this, value, unit)
            TimerAlarm.scheduleAll(this)
            runNowIfConfigured()
        }
    }

    private fun commitGroupWindowSettings() {
        val value = groupWindow.text.toString().trim().toIntOrNull()
        if (value == null || value < 0) {
            groupWindow.error = getString(R.string.err_number)
            return
        }
        groupWindow.error = null
        val unit = groupWindowUnit.selectedItemPosition
        if (value != Prefs.groupValue(this) || unit != Prefs.groupUnit(this)) {
            Prefs.setGroupWindow(this, value, unit)
            TimerAlarm.scheduleAll(this)
        }
    }

    private fun commitLeadSettings() {
        val value = leadValue.text.toString().trim().toIntOrNull()
        if (value == null || value < 0) {
            leadValue.error = getString(R.string.err_number)
            return
        }
        leadValue.error = null
        val unit = leadUnit.selectedItemPosition
        if (value != Prefs.leadValue(this) || unit != Prefs.leadUnit(this)) {
            Prefs.setLead(this, value, unit)
            runNowIfConfigured()
        }
    }

    private fun selectionListener(onSelected: () -> Unit): AdapterView.OnItemSelectedListener =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) = onSelected()

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

    private fun runNowIfConfigured() {
        if (Prefs.hasApiKey(this) && Prefs.farmId(this).isNotBlank()) {
            PollScheduler.refreshNow(this)
        }
    }

    private fun runNow() {
        commitPendingTextSettings()
        if (!Prefs.hasApiKey(this)) {
            showCheckNotStarted(R.string.err_api_key_required)
            return
        }
        if (Prefs.farmId(this).isEmpty()) {
            showCheckNotStarted(R.string.err_farm_id)
            return
        }
        val check = PollScheduler.runNow(this)
        val id = check.id
        farmSettingsChanged = false
        lastCheckPrefs.edit().putString("id", id.toString()).apply()
        showCheckRunning()
        val info = observeCheck(id)
        val enqueue = check.enqueue.result
        enqueue.addListener({
            if (runCatching { enqueue.get() }.isFailure && lastCheckPrefs.getString("id", null) == id.toString()) {
                lastCheckPrefs.edit().remove("id").apply()
                if (!isDestroyed) {
                    info.removeObservers(this)
                    showCheckStatus(getString(R.string.check_enqueue_failed))
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun observeCheck(id: UUID): LiveData<out WorkInfo?> {
        val info = WorkManager.getInstance(this).getWorkInfoByIdLiveData(id)
        info.observe(this) { work ->
            if (work == null || lastCheckPrefs.getString("id", null) != id.toString()) return@observe
            when (work.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> showCheckRunning()
                WorkInfo.State.RUNNING -> {
                    val seconds = work.progress.getInt(PollFeedback.WAIT_SECONDS, 0)
                    val waiting = if (seconds > 0) {
                        getString(
                            R.string.check_result_format,
                            resources.getQuantityString(R.plurals.check_rate_limit_wait, seconds, seconds),
                            work.progress.getString(PollFeedback.DETAILS).orEmpty(),
                        )
                    } else null
                    showCheckRunning(waiting)
                }
                WorkInfo.State.CANCELLED ->
                    showCheckStatus(getString(R.string.check_complete_cancelled))
                WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED -> {
                    val title = when (work.outputData.getString(PollFeedback.OUTCOME)) {
                        PollFeedback.SUCCESS -> R.string.check_complete_success
                        PollFeedback.PARTIAL -> R.string.check_complete_partial
                        PollFeedback.FAILURE -> R.string.check_complete_failure
                        else -> if (work.state == WorkInfo.State.FAILED) R.string.check_complete_failure
                            else R.string.check_complete_unavailable
                    }
                    val details = work.outputData.getString(PollFeedback.DETAILS)
                    showCheckStatus(
                        if (details.isNullOrEmpty()) getString(title)
                        else getString(R.string.check_result_format, getString(title), details),
                    )
                }
            }
            if (work.state.isFinished) info.removeObservers(this)
        }
        return info
    }

    private fun showCheckNotStarted(reason: Int) {
        lastCheckPrefs.edit().remove("id").apply()
        showCheckStatus(getString(R.string.check_not_started, getString(reason)))
    }

    private fun showCheckRunning(waiting: String? = null) {
        checkStatus.text = waiting.orEmpty()
        checkStatus.visibility = if (waiting == null) View.GONE else View.VISIBLE
        if (!testNow.isEnabled) return
        testNow.isEnabled = false
        testNow.alpha = 0.55f
        checkDots = 1
        animateCheckButton.run()
    }

    private fun showCheckStatus(message: String) {
        testNow.removeCallbacks(animateCheckButton)
        testNow.isEnabled = true
        testNow.alpha = 1f
        testNow.setText(R.string.btn_test_now)
        checkStatus.text = message
        checkStatus.visibility = View.VISIBLE
    }

    // ------------------------------------------------ appearance and home tabs

    private fun applyNativeFont() {
        findViewById<Button>(R.id.btnNativeFontSize).text =
            getString(R.string.label_native_font_size_current, NativeFont.percent(this))
        NativeFont.apply(findViewById(R.id.settingsRoot))
    }

    private fun showNativeFontDialog() {
        val sizes = NativeFont.percentages
        val labels = sizes.map { getString(R.string.format_native_font_size, it) } +
            getString(R.string.custom_native_font_size)
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.select_dialog_singlechoice, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also(NativeFont::apply)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.label_native_font_size)
            .setSingleChoiceItems(adapter, sizes.indexOf(NativeFont.percent(this)).takeIf { it >= 0 } ?: sizes.size) { selection, index ->
                selection.dismiss()
                if (index == sizes.size) showCustomNativeFontDialog() else {
                    NativeFont.setPercent(this, sizes[index])
                    applyNativeFont()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.window?.decorView?.let(NativeFont::apply)
    }

    private fun showCustomNativeFontDialog() {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setHint(R.string.custom_native_font_size_hint)
            setText(NativeFont.percent(this@SettingsActivity).toString())
            selectAll()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.custom_native_font_size)
            .setView(field)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val percent = field.text.toString().toIntOrNull()
            if (percent == null || percent <= 0) {
                field.error = getString(R.string.custom_native_font_size_error)
            } else {
                NativeFont.setPercent(this, percent)
                applyNativeFont()
                dialog.dismiss()
            }
        }
    }

    private fun renderHomeTabs() {
        val list = findViewById<LinearLayout>(R.id.homeTabList)
        list.removeAllViews()
        HomeTabs.list(this).forEach { tab ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val label = TextView(this).apply {
                text = "${tab.name}\n${tab.url}"
                setTextColor(getColor(R.color.sfl_text_black))
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                setText(R.string.btn_edit_home_tab)
                setAllCaps(false)
                contentDescription = getString(R.string.desc_edit_home_tab, tab.name)
                setOnClickListener { showHomeTabDialog(tab) }
            })
            row.addView(Button(this).apply {
                setText(R.string.btn_remove_home_tab)
                setAllCaps(false)
                contentDescription = getString(R.string.desc_remove_home_tab, tab.name)
                setOnClickListener {
                    HomeTabs.remove(this@SettingsActivity, tab.id)
                    TabPreviews.remove(this@SettingsActivity, tab)
                    renderHomeTabs()
                }
            })
            list.addView(row)
            NativeFont.apply(row)
        }
    }

    private fun showHomeTabDialog(tab: HomeTab? = null) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        val name = textInput(InputType.TYPE_CLASS_TEXT, R.string.label_home_tab_name)
        val url = textInput(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, R.string.label_home_tab_url)
        name.setText(tab?.name)
        url.setText(tab?.url)
        addField(container, R.string.label_home_tab_name, name)
        addField(container, R.string.label_home_tab_url, url)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (tab == null) R.string.btn_add_home_tab else R.string.edit_home_tab_title)
            .setView(container)
            .setPositiveButton(if (tab == null) R.string.btn_add_home_tab else R.string.save_home_tab, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.window?.decorView?.let(NativeFont::apply)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val tabName = name.text.toString().trim()
            val tabUrl = url.text.toString().trim()
            name.error = if (tabName.isEmpty()) getString(R.string.err_home_tab_name) else null
            url.error = if (!HomeTabs.isValidUrl(tabUrl)) getString(R.string.err_home_tab_url) else null
            if (name.error == null && url.error == null) {
                if (tab == null) {
                    HomeTabs.add(this, tabName, tabUrl)
                } else {
                    HomeTabs.edit(this, tab.id, tabName, tabUrl)
                    if (tab.url != tabUrl) TabPreviews.remove(this, tab)
                }
                renderHomeTabs()
                dialog.dismiss()
            }
        }
    }

    // ------------------------------------------------------ system grants

    private fun requestExactAlarm() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            // The system shows the "Alarms & reminders" page for this app.
            startActivity(NotificationGrants.exactAlarmIntent(this))
        } catch (_: Exception) {
            openAppSettings()
        }
    }

    private fun requestBatteryExemption() {
        if (NotificationGrants.isIgnoringBattery(this)) {
            // Already exempt: the only thing left is to let them revoke it.
            openAppSettings()
            return
        }
        try {
            startActivity(NotificationGrants.batteryExemptionIntent(this))
            Prefs.setIgnoreBatteryAsked(this, true)
        } catch (_: Exception) {
            // Some OEMs strip the prompt; fall back to the app's settings page.
            openAppSettings()
        }
    }

    /** Web and mailto links on the About tab open in the matching external app. */
    private fun openExternalLink(uri: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.settings_link_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            // Nothing to open — the switch simply stays where it is.
        }
    }

    // -------------------------------------------------------- price alerts

    private fun renderAlerts() {
        val alerts = Prefs.alerts(this)
        priceAlertList.removeAllViews()
        priceAlertEmpty.visibility = if (alerts.isEmpty()) View.VISIBLE else View.GONE

        alerts.forEachIndexed { index, alert ->
            val row = layoutInflater.inflate(R.layout.item_price_alert, priceAlertList, false)
            val direction = getString(if (alert.below) R.string.label_below else R.string.label_above)
            row.findViewById<TextView>(R.id.alertText).text =
                getString(R.string.price_alert_row, alert.name, direction, alert.threshold)
            row.findViewById<android.widget.ImageButton>(R.id.btnDelete).setOnClickListener {
                val current = Prefs.alerts(this).toMutableList()
                if (index < current.size) current.removeAt(index)
                Prefs.saveAlerts(this, current)
                renderAlerts()
            }
            priceAlertList.addView(row)
            NativeFont.apply(row)
        }
    }

    private fun showAddAlertDialog() {
        val white = appColor(this, R.color.sfl_text_white)
        val popupBackground = android.graphics.drawable.ColorDrawable(appColor(this, R.color.sfl_page))
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(4)
            setPadding(dp(20), 0, pad, 0)
        }

        val collectionSpinner = spinner(R.array.price_collections)
        val itemInput = AutoCompleteTextView(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 1
            threshold = 0
            hint = getString(R.string.label_item_name)
            setTextColor(white)
            setHintTextColor(white)
            setDropDownBackgroundDrawable(popupBackground)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
        }
        val thresholdInput = textInput(
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            R.string.label_threshold,
        ).apply {
            setTextColor(white)
            setHintTextColor(white)
        }
        val directionSpinner = spinner(R.array.price_directions)

        addField(container, R.string.label_collection, collectionSpinner, white)
        addField(container, R.string.label_item_name, itemInput, white)
        addField(container, R.string.label_direction, directionSpinner, white)
        addField(container, R.string.label_threshold, thresholdInput, white)

        var selectedChoice: PriceAlertChoice? = null
        fun updateChoices(collection: String) {
            val choices = when (collection) {
                "buds" -> (1..BUD_ID_MAX).map { id ->
                    PriceAlertChoice("bud #$id", collection, id, null)
                }

                "pets" -> (1..PET_ID_MAX).map { id ->
                    PriceAlertChoice("pet #$id", collection, id, null)
                }

                else -> ItemIcons.catalogForCollection(this, collection).map { entry ->
                    PriceAlertChoice(entry.name, entry.collection, entry.id, entry.iconPath)
                }
            }
            selectedChoice = null
            itemInput.setAdapter(PriceAlertAdapter(this, choices))
            itemInput.text.clear()
        }

        collectionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateChoices(parent?.getItemAtPosition(position)?.toString().orEmpty())
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateChoices(collectionSpinner.selectedItem.toString())
        itemInput.setOnItemClickListener { parent, _, position, _ ->
            selectedChoice = parent.getItemAtPosition(position) as? PriceAlertChoice
        }
        itemInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && itemInput.text.isEmpty()) itemInput.showDropDown()
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(
            this, R.style.ThemeOverlay_SflBrowser_PriceAlert,
        )
            .setTitle(R.string.alert_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.btn_add_price_alert, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.decorView?.let(NativeFont::apply)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(white)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(white)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val threshold = thresholdInput.text.toString().trim().toDoubleOrNull()
                val choice = selectedChoice
                if (choice == null ||
                    choice.name != itemInput.text.toString() ||
                    choice.collection != collectionSpinner.selectedItem.toString()
                ) {
                    Toast.makeText(this, R.string.err_select_item, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (threshold == null) {
                    Toast.makeText(this, R.string.err_number, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val below = directionSpinner.selectedItemPosition == 0
                val alerts = Prefs.alerts(this).toMutableList()
                alerts.add(PriceAlert(choice.name, choice.collection, choice.id, below, threshold))
                Prefs.saveAlerts(this, alerts)
                renderAlerts()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun textInput(type: Int, labelRes: Int): EditText =
        EditText(this).apply {
            inputType = type or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 1
            hint = getString(labelRes)
            setTextColor(getColor(R.color.sfl_text_black))
            setHintTextColor(getColor(R.color.sfl_text_black))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
        }

    private fun spinner(arrayRes: Int): Spinner =
        Spinner(this).apply {
            setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(getColor(R.color.sfl_page)))
            adapter = nativeSpinnerAdapter(arrayRes, getColor(R.color.sfl_text_white))
        }

    private fun nativeSpinnerAdapter(arrayRes: Int, textColor: Int? = null): ArrayAdapter<CharSequence> =
        object : ArrayAdapter<CharSequence>(
            this, android.R.layout.simple_spinner_item, resources.getTextArray(arrayRes),
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also(::styleRow)

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getDropDownView(position, convertView, parent).also(::styleRow)

            private fun styleRow(view: View) {
                if (textColor != null && view is TextView) view.setTextColor(textColor)
                NativeFont.apply(view)
            }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    private fun addField(
        container: LinearLayout, labelRes: Int, view: View,
        labelColor: Int = getColor(R.color.sfl_text_black),
    ) {
        val label = TextView(this).apply {
            setText(labelRes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(labelColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
        container.addView(label)
        container.addView(
            view,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val OPEN_FARM_SYNC = "com.sfl.browser.OPEN_FARM_SYNC"
        private const val FARM_SYNC_TAB = 1
        private const val BUD_ID_MAX = 2621
        private const val PET_ID_MAX = 3000

        internal fun farmSyncIntent(context: Context) =
            Intent(context, SettingsActivity::class.java).setAction(OPEN_FARM_SYNC)
    }
}

private data class TimerCategoryOption(
    val category: TimerCategory,
    val label: Int,
)

private const val TEXT_COMMIT_DELAY_MS = 400L

private data class PriceAlertChoice(
    val name: String,
    val collection: String,
    val id: Int,
    val iconPath: String?,
) {
    override fun toString(): String = name
}

private class PriceAlertAdapter(
    context: Context,
    private val allChoices: List<PriceAlertChoice>,
) : ArrayAdapter<PriceAlertChoice>(context, 0, allChoices) {

    private val searchFilter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val query = constraint?.toString()?.trim().orEmpty()
            val matches = if (query.isEmpty()) {
                allChoices
            } else {
                allChoices.filter { it.name.contains(query, ignoreCase = true) }
            }
            return FilterResults().apply {
                values = matches
                count = matches.size
            }
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults) {
            val matches = (results.values as? List<*>)
                .orEmpty()
                .filterIsInstance<PriceAlertChoice>()
            clear()
            addAll(matches)
            if (matches.isEmpty()) notifyDataSetInvalidated() else notifyDataSetChanged()
        }
    }

    override fun getFilter(): Filter = searchFilter

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        bindRow(position, convertView, parent)

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        bindRow(position, convertView, parent)

    private fun bindRow(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_price_alert_choice, parent, false)
        val choice = getItem(position) ?: return row
        row.findViewById<TextView>(R.id.itemName).text = choice.name

        val icon = row.findViewById<ImageView>(R.id.itemIcon)
        val drawable = choice.iconPath?.let { ItemIcons.drawable(context, it, maxPx = 64) }
        if (drawable == null) {
            icon.visibility = View.GONE
            icon.setImageDrawable(null)
        } else {
            icon.visibility = View.VISIBLE
            icon.setImageDrawable(drawable)
        }
        NativeFont.apply(row)
        return row
    }
}
