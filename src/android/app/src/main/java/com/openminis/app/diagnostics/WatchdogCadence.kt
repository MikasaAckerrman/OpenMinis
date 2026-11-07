package com.openminis.app.diagnostics

/**
 * [B7] Adaptive cadence for the app-lifetime watchdogs.
 *
 * The HangDetector used to ping the main thread every 1s and poll its watch
 * thread every 500ms FOREVER — foreground, backgrounded, idle, didn't
 * matter. On the iQOO Neo 10 that constant wake pattern is a measurable
 * slice of the idle CPU budget (the B7 report: ~9-12% background CPU from
 * our own watchdogs + the external guard modules). The cadence now follows
 * the activity state:
 *
 *  - **Foreground** — unchanged 1s heartbeat / 500ms poll: UI-stall
 *    diagnostics need the full fidelity, and a foreground app is allowed
 *    to wake.
 *  - **Background + inference in flight** (`activeSessions > 0`) — 5s:
 *    background hangs are REAL (the 07.10 pthread_create deaths happened
 *    invisibly in the background), so the detector stays alive, just
 *    coarse.
 *  - **Background + idle** — 30s: a UI-stall watchdog has nothing to watch
 *    while the UI is invisible and no work runs; this is the "fully
 *    asleep" state of the heartbeat — one cheap ping every 30s instead of
 *    one every second.
 *
 * The hang threshold derives from the interval with the SAME margin the
 * original constants encoded (1000ms heartbeat + 1500ms margin = the old
 * flat 2500ms): a healthy main thread is observed with a gap of
 * [interval, 2×interval), and the threshold must clear that margin.
 *
 * The TurnGuard mtime heartbeat is NOT this object's business: it is
 * already event-scoped (started when a streamJob claims its slot, stopped
 * in its finally — it never ticks at idle), and its 15s cadence is part of
 * the external minis_turnguard freshness contract (45s window = 3 beats).
 */
object WatchdogCadence {

    fun heartbeatIntervalMs(appForeground: Boolean, activeSessions: Int): Long = when {
        appForeground -> 1_000L
        activeSessions > 0 -> 5_000L
        else -> 30_000L
    }

    fun watchPollMs(appForeground: Boolean, activeSessions: Int): Long = when {
        appForeground -> 500L
        activeSessions > 0 -> 5_000L
        else -> 30_000L
    }

    /** Threshold keeps its margin over the ACTUAL heartbeat interval. */
    fun hangThresholdMs(heartbeatIntervalMs: Long): Long = heartbeatIntervalMs + 1_500L

    /**
     * Whether hang evaluation runs at all. Background + idle: the main
     * thread is EXPECTED to go silent for whole minutes (OS freeze), every
     * beat would look like a "hang", and the idle-filter would just park
     * them — evaluating is pure noise, so the watch loop skips it.
     */
    fun watchActive(appForeground: Boolean, activeSessions: Int): Boolean =
        appForeground || activeSessions > 0
}
