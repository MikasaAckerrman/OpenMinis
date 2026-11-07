package com.openminis.app.daemon

/**
 * [M-R1] Crash-backoff escalation for the daemon supervisor: 1000 ms ->
 * 2000 ms -> 4000 ms -> ... -> 60 000 ms (doubling, capped). Only a
 * RAPID-crash loop escalates: a daemon that stayed up longer than
 * [stableUptimeMs] before dying counts as a fresh start (its death
 * resets the escalation); a deliberate stop() is not a crash at all
 * (see [onCleanStop]).
 *
 * Pure JVM on purpose — the reviewer's M-R1 test gate lives here, not
 * in the process-handling supervisor.
 */
class SupervisorBackoffPolicy(
    private val baseDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 60_000L,
    private val stableUptimeMs: Long = 60_000L,
) {
    private var consecutiveFailures = 0
    private var lastDelayMs = 0L

    /** Call when the daemon died on its own. Returns the restart delay. */
    @Synchronized
    fun onDeath(uptimeMs: Long): Long {
        // Strictly greater, per the reviewer's plan ("аптайме >60 с"):
        // exactly 60 s is still inside the escalation window.
        if (uptimeMs > stableUptimeMs) consecutiveFailures = 0
        consecutiveFailures += 1
        var delay = baseDelayMs
        // failures-1 doublings; the cap makes every step saturate at
        // maxDelayMs, so a bounded repeat count is provable and the
        // arithmetic can never overflow.
        repeat((consecutiveFailures - 1).coerceAtMost(8)) {
            delay = (delay * 2).coerceAtMost(maxDelayMs)
        }
        lastDelayMs = delay
        return delay
    }

    /** A deliberate stop() is not a crash — reset the escalation. */
    @Synchronized
    fun onCleanStop() {
        consecutiveFailures = 0
        lastDelayMs = 0L
    }

    /** For diagnostics and tests. */
    @Synchronized
    fun currentConsecutiveFailures(): Int = consecutiveFailures
}
