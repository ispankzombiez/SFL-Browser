package com.sfl.browser.notify

import com.sfl.browser.data.TimerCategory

internal data class TimerNotificationGroup(
    val timers: List<FarmTimer>,
    val fireAt: Long,
    val craftingBoxQueue: Boolean = false,
    val cropMachineQueue: String? = null,
) {
    val alarmKey: String get() = when {
        craftingBoxQueue -> "group:crafting-box"
        cropMachineQueue != null -> "group:$cropMachineQueue"
        else -> "group:${timers.first().key}"
    }
    val yields: List<FarmYield>? get() =
        FarmYield.totals(timers, allowMixedItems = craftingBoxQueue || cropMachineQueue != null)
}

internal val FarmTimer.isMachineOilAlert: Boolean
    get() = category == TimerCategory.CROP_MACHINE && key.startsWith("machineOil:")

internal fun FarmTimer.notificationStamp(offsetMs: Long): Long = when {
    category == TimerCategory.ANIMAL_LOVE -> 1L
    isMachineOilAlert -> readyAt
    else -> readyAt + offsetMs
}

internal fun planTimerNotifications(
    timers: List<FarmTimer>,
    windowMs: Long,
    offsetMs: Long,
    now: Long = Long.MIN_VALUE,
    craftingBoxAllDone: Boolean = false,
    cropMachineAllDone: Boolean = false,
    alreadyFired: (key: String, fireAt: Long) -> Boolean,
): List<TimerNotificationGroup> {
    fun fireAt(timer: FarmTimer): Long =
        timer.readyAt + when {
            timer.isMachineOilAlert -> 0L
            timer.expiresAt != null -> maxOf(0L, offsetMs)
            else -> offsetMs
        }
    val pending = timers.filter { timer ->
        !alreadyFired(timer.key, timer.notificationStamp(offsetMs)) &&
            (timer.expiresAt == null || maxOf(now, fireAt(timer)) < timer.expiresAt)
    }
    val (crafting, other) = pending.partition {
        craftingBoxAllDone && it.category == TimerCategory.CRAFTING_BOX
    }
    val (oil, nonOil) = other.partition(FarmTimer::isMachineOilAlert)
    val (machine, nonMachine) = nonOil.partition {
        cropMachineAllDone && it.category == TimerCategory.CROP_MACHINE
    }
    val (love, regular) = nonMachine.partition { it.category == TimerCategory.ANIMAL_LOVE }
    return buildList {
        Notifier.groupTimers(regular, windowMs).forEach {
            add(TimerNotificationGroup(it, fireAt(it.last())))
        }
        if (crafting.isNotEmpty()) {
            val last = timers.filter { it.category == TimerCategory.CRAFTING_BOX }.maxOf(::fireAt)
            add(TimerNotificationGroup(crafting.sortedBy { it.readyAt }, last, craftingBoxQueue = true))
        }
        oil.forEach { add(TimerNotificationGroup(listOf(it), it.readyAt)) }
        val latestByMachine = timers.asSequence()
            .filter { it.category == TimerCategory.CROP_MACHINE && it.machineQueueCanFinish }
            .groupingBy { it.key.substringBeforeLast(':') }
            .fold(0L) { latest, timer -> maxOf(latest, timer.readyAt) }
        for ((key, packs) in machine.groupBy { it.key.substringBeforeLast(':') }) {
            if (packs.any { !it.machineQueueCanFinish }) continue
            val latest = latestByMachine[key] ?: continue
            add(TimerNotificationGroup(packs.sortedBy { it.readyAt }, latest + offsetMs, cropMachineQueue = key))
        }
        for (batch in Notifier.groupTimers(love, windowMs)) {
            var group = mutableListOf<FarmTimer>()
            var expires = Long.MAX_VALUE
            for (timer in batch) {
                if (group.isNotEmpty() && fireAt(timer) >= expires) {
                    add(TimerNotificationGroup(group, fireAt(group.last())))
                    group = mutableListOf()
                    expires = Long.MAX_VALUE
                }
                group.add(timer)
                expires = minOf(expires, timer.expiresAt ?: Long.MAX_VALUE)
            }
            add(TimerNotificationGroup(group, fireAt(group.last())))
        }
    }
}
