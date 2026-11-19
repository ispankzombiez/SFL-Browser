package com.sfl.browser.notify

import org.json.JSONObject

internal data class AnimalLove(val opensAt: Long, val slot: Int, val item: String) {
    companion object {
        fun next(animal: JSONObject, wakesAt: Long, now: Long): AnimalLove? {
            val asleepAt = animal.optLong("asleepAt")
            if (asleepAt <= 0 || asleepAt > now || wakesAt <= now || wakesAt <= asleepAt) return null
            if (animal.optString("state") == "sick") return null
            val item = animal.optString("item").takeUnless { it.isBlank() || it == "null" } ?: return null
            val lovedAt = animal.optLong("lovedAt")
            val spent = if (lovedAt > asleepAt) animal.optInt("lovesThisSleep", 1).coerceAtLeast(1) else 0
            if (spent >= 2) return null
            val third = (wakesAt - asleepAt + 2) / 3
            val at = maxOf(asleepAt, lovedAt) + third
            return if (at < wakesAt) AnimalLove(at, spent + 1, item) else null
        }
    }
}
