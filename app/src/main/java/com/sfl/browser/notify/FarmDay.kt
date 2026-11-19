package com.sfl.browser.notify

internal object FarmDay {
    const val DAY_MS = 86_400_000L

    fun index(at: Long): Long = Math.floorDiv(at, DAY_MS)
    fun start(at: Long): Long = index(at) * DAY_MS
    fun nextReset(at: Long): Long = start(at) + DAY_MS

    fun isDailyTask(timer: FarmTimer): Boolean =
        timer.key == "quest:telegram" || timer.key == "budbox:daily"

    fun isVisibleTask(timer: FarmTimer, now: Long): Boolean =
        isDailyTask(timer) && timer.readyAt == start(now) && timer.expiresAt == nextReset(now)
}
