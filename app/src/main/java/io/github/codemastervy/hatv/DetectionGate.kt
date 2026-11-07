package io.github.codemastervy.hatv

/**
 * One pop-up per visit.
 *
 * A visit starts when the person sensor turns "on" and ends once the sensor has stayed "off" for
 * [quietMs]. Person sensors (e.g. Reolink) flicker on/off every few seconds while someone stands at
 * the door; without this, every flicker would count as a new visitor and the pop-up would keep
 * coming back after it auto-closes.
 *
 * The first state seen after the service starts never shows a pop-up: if the sensor is already
 * "on" then, that is treated as an ongoing visit (no stale alerts after a reboot).
 * unavailable/unknown states are ignored for timing.
 */
class DetectionGate(var quietMs: Long = 60_000) {
    private var last: String? = null
    private var inVisit = false
    private var offSince: Long? = null

    /** @return true if the pop-up should show now. */
    fun onPersonState(state: String, nowMs: Long): Boolean {
        val prev = last
        last = state
        return when (state) {
            "on" -> {
                val off = offSince
                if (inVisit && off != null && nowMs - off >= quietMs) inVisit = false
                offSince = null
                val show = prev != null && prev != "on" && !inVisit
                if (prev == null || show) inVisit = true
                show
            }
            "off" -> {
                if (prev != "off") offSince = nowMs
                false
            }
            else -> false
        }
    }

    /** Debug only: forget the current visit so the next "on" shows immediately. */
    fun reset() {
        inVisit = false
        offSince = null
        if (last == null || last == "on") last = "off"
    }
}

/** Reconnect delays in seconds: 2, 4, 8, 16, 32, 60, 60, ... */
class Backoff(private val minS: Long = 2, private val maxS: Long = 60) {
    private var current = minS
    fun next(): Long = current.also { current = (current * 2).coerceAtMost(maxS) }
    fun reset() { current = minS }
    fun toMax() { current = maxS }
}
