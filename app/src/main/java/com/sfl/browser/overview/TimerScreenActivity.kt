package com.sfl.browser.overview

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.Lifecycle
import com.sfl.browser.ui.PaletteActivity
import com.sfl.browser.ui.AppPalette
import com.sfl.browser.ui.appColor
import com.sfl.browser.ui.applyPaletteBackground
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.sfl.browser.R
import com.sfl.browser.data.ItemIcons
import com.sfl.browser.data.Prefs
import com.sfl.browser.data.TimerFeed
import com.sfl.browser.notify.FarmTimer
import com.sfl.browser.notify.FarmDay
import com.sfl.browser.notify.FarmYield
import com.sfl.browser.notify.Notifier
import com.sfl.browser.notify.isMachineOilAlert
import java.text.NumberFormat
import com.sfl.browser.ui.SystemBarInsets
import com.sfl.browser.ui.NativeFont
import com.sfl.browser.ui.dp

/**
 * Shell both timer screens share: the title toolbar, a swipe-to-refresh
 * list, a one-second countdown tick and the cache-then-refresh feed.
 *
 * Subclasses only decide *how* the timers are arranged.
 */
abstract class TimerScreenActivity : PaletteActivity() {

    /** Title + subtitle above the list; [subtitleRes] is the static blurb. */
    @get:StringRes
    protected abstract val titleRes: Int

    @get:StringRes
    protected abstract val subtitleRes: Int

    /** Shown when the farm reports nothing to wait for. */
    @get:StringRes
    protected abstract val emptyRes: Int
    protected open val includeEvents = false

    protected lateinit var container: LinearLayout
    protected lateinit var swipe: SwipeRefreshLayout

    protected var timers: List<FarmTimer> = emptyList()

    /** Every visible countdown chip, so the per-second tick can update in place. */
    private data class Chip(val view: TextView, val timer: FarmTimer)
    private val chips = mutableListOf<Chip>()
    private val expandedGroups = mutableSetOf<List<String>>()
    private val collapsedQueues = mutableSetOf<TimerUi.Category>()
    private var nextReset = FarmDay.nextReset(System.currentTimeMillis())

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateChips()
            handler.postDelayed(this, 1_000L)
        }
    }

    private var fetchedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timer_screen)
        SystemBarInsets.apply(window, findViewById(R.id.timerScreenRoot))

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(titleRes)
        toolbar.setNavigationOnClickListener { finish() }
        findViewById<TextView>(R.id.txtSubtitle).setText(subtitleRes)
        container = findViewById(R.id.container)
        swipe = findViewById(R.id.swipe)
        swipe.setColorSchemeColors(
            getColor(R.color.sfl_chip_soon),
            getColor(R.color.sfl_chip_ready),
            getColor(R.color.sfl_chip_later),
        )
        swipe.setOnRefreshListener { refresh() }
        NativeFont.apply(findViewById(R.id.timerScreenRoot))
        toolbar.post { NativeFont.apply(toolbar) }

        TimerFeed.load(
            this,
            includeEvents = includeEvents,
            onResult = { result -> runOnUiThread { onResult(result) } },
            onDone = { runOnUiThread { swipe.isRefreshing = false } },
        )
        handler.post(ticker)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun refresh() {
        swipe.isRefreshing = true
        TimerFeed.load(
            this,
            includeEvents = includeEvents,
            onResult = { result -> runOnUiThread { onResult(result) } },
            onDone = { runOnUiThread { swipe.isRefreshing = false } },
        )
    }

    private fun onResult(result: TimerFeed.Result) {
        val now = System.currentTimeMillis()
        timers = result.timers.filter {
            it.category != com.sfl.browser.data.TimerCategory.ANIMAL_LOVE && !it.isMachineOilAlert &&
                (!FarmDay.isDailyTask(it) || FarmDay.isVisibleTask(it, now))
        }
        fetchedAt = result.fetchedAt
        chips.clear()
        render()
        NativeFont.apply(container)
        updateSubtitle()
        if (result.eventsRefreshed == false) {
            Toast.makeText(this, R.string.timeline_events_not_refreshed, Toast.LENGTH_LONG).show()
        }

        if (!result.refreshed && result.failure != null) {
            val message = when (result.failure) {
                TimerFeed.Failure.NO_KEY -> getString(R.string.err_api_key_required)
                TimerFeed.Failure.NO_FARM -> getString(R.string.err_farm_id)
                TimerFeed.Failure.BUSY -> getString(R.string.err_busy)
                else -> getString(R.string.err_api_failed, result.message ?: "?")
            }
            // Only nag when there is nothing at all to show — a stale-but-full
            // list is not worth a toast.
            if (timers.isEmpty()) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateSubtitle() {
        val subtitle = findViewById<TextView>(R.id.txtSubtitle) ?: return
        if (fetchedAt <= 0L) {
            subtitle.setText(subtitleRes)
            return
        }
        val age = System.currentTimeMillis() - fetchedAt
        val label = if (age < 15_000L) getString(R.string.just_now) else ago(age)
        subtitle.text = getString(R.string.refreshed_ago, label)
    }

    private fun ago(ageMs: Long): String {
        val min = ageMs / 60_000L
        return when {
            min < 1 -> getString(R.string.just_now)
            min < 60 -> "${min}m ago"
            else -> "${min / 60}h ${min % 60}m ago"
        }
    }

    /** Rebuild the whole list from [timers]; subclasses supply the layout. */
    protected abstract fun render()

    /** [render] again after a UI-only change (e.g. expanding a rolled-up section). */
    protected fun rerender() {
        chips.clear()
        render()
        NativeFont.apply(container)
    }

    /** Inflate one timer row under [parent] and register its countdown chip. */
    protected fun addRow(parent: LinearLayout, timer: FarmTimer) {
        val row = LayoutInflater.from(this).inflate(R.layout.view_timer_row, parent, false)
        row.findViewById<TextView>(R.id.txtLabel).text = timer.label
        val detail = row.findViewById<TextView>(R.id.txtDetail)
        val products = FarmYield.forTimer(timer)
        detail.visibility = if (products.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (!products.isNullOrEmpty()) {
            if (products.size > 1) detail.maxLines = Int.MAX_VALUE
            detail.text = products.joinToString(", ", transform = ::formatYield)
        }

        val icon = row.findViewById<ImageView>(R.id.imgIcon)
        val art = ItemIcons.drawable(this, timer.icon)
            ?: ItemIcons.drawableForName(this, timer.label)
        if (art != null) {
            icon.setImageDrawable(art)
            icon.visibility = View.VISIBLE
        } else {
            icon.visibility = View.GONE
        }

        val status = row.findViewById<TextView>(R.id.txtStatus)
        if (FarmDay.isDailyTask(timer)) {
            row.findViewById<TextView>(R.id.txtLabel).maxLines = 2
            status.setText(R.string.timer_not_done)
            status.textSize = 15f
            status.minHeight = dp(34)
            applyPaletteBackground(status, R.color.sfl_chip_danger, 5)
            status.setTextColor(appColor(this, R.color.sfl_on_chip_danger))
        } else {
            registerStatus(status, timer)
        }
        parent.addView(row)
    }

    protected fun addGroupedRows(parent: LinearLayout, items: List<FarmTimer>) {
        groupedRows(items).forEach { addTimerGroup(parent, it) }
    }

    protected fun groupedRows(items: List<FarmTimer>): List<List<FarmTimer>> =
        Notifier.groupTimers(items, Prefs.groupWindowMillis(this)).sortedBy { it.first().readyAt }

    protected fun addRaffleStart(parent: LinearLayout, timer: FarmTimer) {
        val row = layoutInflater.inflate(R.layout.view_timer_group_row, parent, false)
        row.findViewById<TextView>(R.id.txtLabel).text = timer.label
        row.findViewById<View>(R.id.imgIcon).visibility = View.GONE
        row.findViewById<View>(R.id.txtDetail).visibility = View.GONE
        registerStatus(row.findViewById(R.id.txtStatus), timer)
        parent.addView(row)
        if (bindExpansion(row, listOf(timer.key))) {
            val details = TextView(this).apply {
                text = timer.detail.ifBlank { getString(R.string.timeline_prizes_unavailable, timer.label) }
                textSize = 14f
                setTextColor(appColor(context, R.color.sfl_text_black))
                setPadding(dp(12), dp(6), dp(12), dp(12))
            }
            parent.addView(details)
        }
    }

    protected fun addTimerGroup(parent: LinearLayout, group: List<FarmTimer>) {
        if (group.size == 1) {
            addRow(parent, group.single())
            return
        }
        val row = LayoutInflater.from(this).inflate(R.layout.view_timer_group_row, parent, false)
        val first = group.first()
        row.findViewById<TextView>(R.id.txtLabel).text = first.label
        val count = getString(R.string.timer_group_count, group.size)
        val totals = FarmYield.totals(group)
        val detail = row.findViewById<TextView>(R.id.txtDetail)
        if ((totals?.size ?: 0) > 1) detail.maxLines = Int.MAX_VALUE
        detail.text = if (totals.isNullOrEmpty()) count else {
            getString(R.string.timer_group_with_yield, count, totals.joinToString(", ", transform = ::formatYield))
        }
        val icon = row.findViewById<ImageView>(R.id.imgIcon)
        val art = ItemIcons.drawable(this, first.icon)
            ?: ItemIcons.drawableForName(this, first.label)
        if (art == null) icon.visibility = View.GONE else icon.setImageDrawable(art)
        registerStatus(row.findViewById(R.id.txtStatus), first)

        val expanded = bindExpansion(row, group.map { it.key }.sorted())
        parent.addView(row)
        if (expanded) group.forEach { addRow(parent, it) }
    }

    private fun bindExpansion(row: View, key: List<String>): Boolean {
        val expanded = key in expandedGroups
        val toggle = row.findViewById<ImageButton>(R.id.btnExpand)
        toggle.setImageResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        toggle.contentDescription = getString(
            if (expanded) R.string.cd_collapse_group else R.string.cd_expand_group,
        )
        toggle.setOnClickListener {
            if (expanded) expandedGroups.remove(key) else expandedGroups.add(key)
            rerender()
        }

        return expanded
    }

    protected fun bindQueueSection(panel: View, category: TimerUi.Category, queue: List<FarmTimer>) {
        val title = panel.findViewById<TextView>(R.id.txtSectionTitle)
        val rows = panel.findViewById<LinearLayout>(R.id.rows)
        title.text = "${category.title} · ${queue.size}"
        fun update() {
            val collapsed = category in collapsedQueues
            rows.visibility = if (collapsed) View.GONE else View.VISIBLE
            title.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, if (collapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less, 0,
            )
            val action = getString(if (collapsed) R.string.cd_expand_group else R.string.cd_collapse_group)
            title.contentDescription = "${title.text}, $action"
        }
        title.setOnClickListener {
            if (!collapsedQueues.add(category)) collapsedQueues.remove(category)
            update()
        }
        addGroupedRows(rows, queue)
        update()
    }

    private fun formatYield(yield: FarmYield): String = getString(
        R.string.timer_yield,
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = 4 }.format(yield.amount),
        yield.item,
    )

    /** Apply and register a countdown chip so it stays live during the ticker. */
    private fun registerStatus(view: TextView, timer: FarmTimer) {
        val chip = Chip(view, timer)
        applyStatus(chip, System.currentTimeMillis())
        chips += chip
    }

    /** Empty-state line inside the container. */
    protected fun addEmpty() {
        val view = TextView(this)
        view.text = getString(emptyRes)
        view.setTextColor(getColor(R.color.sfl_text_white))
        view.setShadowLayer(2f, 0f, 2f, getColor(R.color.sfl_page))
        view.textSize = 14f
        view.setPadding(8, 24, 8, 24)
        container.addView(view)
    }

    private fun applyStatus(chip: Chip, now: Long) {
        val (view, timer) = chip
        val status = TimerUi.statusOf(timer.readyAt, now)
        view.text = if (status == TimerUi.Status.READY && timer.key.startsWith("event:")) {
            getString(if (timer.key.endsWith(":start")) R.string.timeline_event_started
                else R.string.timeline_event_ended)
        } else TimerUi.formatRemaining(timer.readyAt, now)
        if (AppPalette.read(this).colors().isEmpty()) {
            view.setBackgroundResource(TimerUi.chipBackground(status))
        } else {
            applyPaletteBackground(view, when (status) {
                TimerUi.Status.READY -> R.color.sfl_chip_ready
                TimerUi.Status.SOON -> R.color.sfl_chip_soon
                TimerUi.Status.LATER -> R.color.sfl_chip_later
            }, 5)
        }
        view.setTextColor(appColor(this, TimerUi.chipTextColor(status)))
    }

    /** Per-second refresh of every visible countdown, without rebuilding views. */
    private fun updateChips() {
        val now = System.currentTimeMillis()
        if (now >= nextReset && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            nextReset = FarmDay.nextReset(now)
            timers = timers.filter { !FarmDay.isDailyTask(it) || FarmDay.isVisibleTask(it, now) }
            rerender()
            if (!swipe.isRefreshing && Prefs.farmId(this).isNotBlank() && Prefs.hasApiKey(this)) refresh()
        }
        chips.forEach { applyStatus(it, now) }
        updateSubtitle()
    }
}
