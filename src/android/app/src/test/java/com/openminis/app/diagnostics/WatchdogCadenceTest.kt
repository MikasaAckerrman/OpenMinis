package com.openminis.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B7] The adaptive watchdog cadence: full fidelity in the foreground,
 * coarse-but-alive while inference runs in the background, and the
 * "fully asleep" 30s state when nothing is happening. The threshold
 * derivation must reproduce the historical flat 2500ms for the foreground
 * heartbeat (1000 + 1500) so the breaker semantics are unchanged.
 */
class WatchdogCadenceTest {

    @Test
    fun `foreground keeps the historical fidelity`() {
        assertEquals(1_000L, WatchdogCadence.heartbeatIntervalMs(appForeground = true, activeSessions = 0))
        assertEquals(500L, WatchdogCadence.watchPollMs(appForeground = true, activeSessions = 0))
        // Even with inference running, foreground is the fast path.
        assertEquals(1_000L, WatchdogCadence.heartbeatIntervalMs(appForeground = true, activeSessions = 3))
    }

    @Test
    fun `background inference stays coarse but alive at 5 seconds`() {
        assertEquals(5_000L, WatchdogCadence.heartbeatIntervalMs(appForeground = false, activeSessions = 1))
        assertEquals(5_000L, WatchdogCadence.watchPollMs(appForeground = false, activeSessions = 2))
    }

    @Test
    fun `background idle is the fully asleep 30-second state`() {
        assertEquals(30_000L, WatchdogCadence.heartbeatIntervalMs(appForeground = false, activeSessions = 0))
        assertEquals(30_000L, WatchdogCadence.watchPollMs(appForeground = false, activeSessions = 0))
    }

    @Test
    fun `threshold derives from the interval with the historical margin`() {
        // 1000ms heartbeat + 1500ms margin == the old flat 2500ms — the
        // foreground breaker semantics are byte-identical.
        assertEquals(2_500L, WatchdogCadence.hangThresholdMs(1_000L))
        // A coarse background heartbeat scales the threshold, so a cadence
        // switch never turns every beat into a phantom hang.
        assertEquals(6_500L, WatchdogCadence.hangThresholdMs(5_000L))
        assertEquals(31_500L, WatchdogCadence.hangThresholdMs(30_000L))
    }

    @Test
    fun `evaluation gate is active in foreground or with live sessions only`() {
        assertTrue(WatchdogCadence.watchActive(appForeground = true, activeSessions = 0))
        assertTrue(WatchdogCadence.watchActive(appForeground = false, activeSessions = 1))
        assertFalse(WatchdogCadence.watchActive(appForeground = false, activeSessions = 0))
    }

    @Test
    fun `the wake path never leaves the detector in a stale state`() {
        // The transition bg-idle -> active must re-baseline (see HangDetector
        // watchLoop): before the first real beat lands, the gap reading is
        // up to a full idle interval stale — larger than ANY threshold. The
        // policy guarantee the loop relies on: every active state has an
        // interval strictly BELOW the corresponding threshold's margin, so
        // after the re-baseline a healthy thread never trips.
        val fgInterval = WatchdogCadence.heartbeatIntervalMs(true, 0)
        assertTrue(WatchdogCadence.hangThresholdMs(fgInterval) > fgInterval)
        val bgInterval = WatchdogCadence.heartbeatIntervalMs(false, 1)
        assertTrue(WatchdogCadence.hangThresholdMs(bgInterval) > bgInterval)
    }
}
