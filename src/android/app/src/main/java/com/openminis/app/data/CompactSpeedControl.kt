package com.openminis.app.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * [T-compact-aimd] User request (12.10): compaction must find its own
 * maximum STABLE speed per model/relay — "rate limited will be sent, deal
 * with it". Classic AIMD (Additive-Increase / Multiplicative-Decrease),
 * the same family TCP congestion control uses: grow lanes on success,
 * halve on the first rate/quota marker, floor at 1. The controller also
 * remembers the last stable speed per provider key so the next compaction
 * starts near the optimum instead of probing from scratch.
 *
 * Pure Kotlin, no Android imports — fully unit-testable.
 */
object CompactSpeedControl {

    const val MIN_LANES = 1
    // [T-compact-aimd-coldstart] unknown provider starts at 2 lanes, not 1:
    // the first batch doubles as a parallel-safety probe (a rate marker
    // halves it back immediately — the cost of one extra 429-able call is
    // cheaper than a whole run at floor speed when the relay is fine).
    const val COLD_START_LANES = 2
    const val MAX_LANES = 8

    /** Stable-speed memory: providerKey -> last stable lane count. */
    private val lastStable = ConcurrentHashMap<String, Int>()

    fun startLanes(providerKey: String?): Int =
        if (providerKey.isNullOrBlank()) COLD_START_LANES
        else (lastStable[providerKey] ?: COLD_START_LANES).coerceIn(MIN_LANES, MAX_LANES)

    /** Live per-run controller state. */
    class Run(initialLanes: Int) {
        private val lanes = AtomicInteger(initialLanes.coerceIn(MIN_LANES, MAX_LANES))
        private val consecutiveRateMarkers = AtomicInteger(0)
        private val lastRateMarkerMs = AtomicLong(0)

        val current: Int get() = lanes.get().coerceIn(MIN_LANES, MAX_LANES)

        /** Window completed OK: additive increase. Returns new lane count. */
        fun onSuccess(): Int {
            consecutiveRateMarkers.set(0)
            return lanes.updateAndGet { if (it < MAX_LANES) it + 1 else MAX_LANES }
        }

        /**
         * Rate/quota marker observed: multiplicative decrease. Returns the
         * new lane count. Two markers within 30s keep halving — the relay
         * is genuinely quota-tight; the run settles at MIN_LANES.
         */
        fun onRateMarker(): Int {
            val now = System.currentTimeMillis()
            val within = now - lastRateMarkerMs.get() < 30_000L
            lastRateMarkerMs.set(now)
            if (!within) consecutiveRateMarkers.set(0)
            consecutiveRateMarkers.incrementAndGet()
            return lanes.updateAndGet { lane ->
                val next = if (lane <= 2) MIN_LANES else lane / 2
                next.coerceAtLeast(MIN_LANES)
            }
        }

        /** Terminal: persist the stable speed for the next run. */
        fun finish(providerKey: String?, windowsSucceeded: Int, windowsTotal: Int) {
            if (providerKey.isNullOrBlank()) return
            // Only remember speeds from mostly-successful runs — a run that
            // failed most windows tells us nothing about sustainable speed.
            if (windowsTotal > 0 && windowsSucceeded * 2 >= windowsTotal) {
                lastStable[providerKey] = current
            }
        }
    }
}
