package com.openminis.app.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-hangdetector-threshold-vs-heartbeat] The detector's constants have to
 * satisfy a relationship, and nothing in the code or the tests said so.
 *
 * The gap between two heartbeats can never be smaller than the heartbeat
 * interval: the callback that updates the timestamp runs on the main thread,
 * so a healthy main thread still produces a gap of one full interval. A
 * threshold below that interval therefore reports every heartbeat as a hang.
 *
 * It did. The threshold was 800ms against a 1000ms heartbeat, so the detector
 * fired roughly once a second, wrote 7188 records to the stall log in a
 * day, and drove the persisted hang count to 35 - which trips the render
 * breaker (streaming markdown degrades to plain text) and the launch
 * breaker (the app opens on home instead of the chosen session).
 *
 * These tests read the real constants out of the production object, so a
 * future edit that breaks the relationship fails here rather than silently
 * on a user's phone.
 */
class HangDetectorInvariantTest {

    private fun constant(name: String): Long {
        val field = HangDetector::class.java.getDeclaredField(name)
        field.isAccessible = true
        // A const val in an object is a static field, so get(null) is right;
        // the Number cast keeps this working if one of them ever becomes Int.
        return (field.get(null) as Number).toLong()
    }

    @Test
    fun `the threshold must exceed the heartbeat interval`() {
        val interval = constant("HEARTBEAT_INTERVAL_MS")
        val threshold = constant("HANG_THRESHOLD_MS")

        assertTrue(
            "a healthy main thread produces a gap of at least the heartbeat " +
                "interval (${interval}ms), so the threshold (${threshold}ms) must be " +
                "above it or every heartbeat is reported as a hang",
            threshold > interval,
        )
    }

    @Test
    fun `the threshold must leave room for the watchdog poll granularity`() {
        val interval = constant("HEARTBEAT_INTERVAL_MS")
        val threshold = constant("HANG_THRESHOLD_MS")

        // The watchdog polls every 500ms, so the observed gap can be up to
        // one poll interval larger than the truth. A margin of at least that
        // much keeps a healthy main thread from ever tripping the detector.
        val pollGranularity = 500L
        assertTrue(
            "threshold (${threshold}ms) must exceed the heartbeat interval " +
                "(${interval}ms) plus the watchdog poll granularity " +
                "(${pollGranularity}ms), otherwise scheduling jitter alone " +
                "reports a hang",
            threshold >= interval + pollGranularity,
        )
    }

    @Test
    fun `the breaker window must be shorter than a day so stale counts expire`() {
        val window = constant("BREAKER_VALID_WINDOW_MS")
        assertTrue(
            "a breaker window of ${window}ms means a hang from yesterday still " +
                "decides how the app opens today",
            window < 24L * 60L * 60L * 1000L,
        )
    }
}
