package com.openminis.app.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [B13-idle-watchdog] Boundary contract of the pure watchdog policy. These
 * semantics are load-bearing on-device: a false Abort kills a live
 * scheduled task, a missed Abort burns the full ceiling on a hung socket.
 */
class RunWatchdogPolicyTest {

    private val IDLE = 3 * 60_000L
    private val MAX = 30 * 60_000L

    @Test
    fun `within both budgets continues`() {
        assertEquals(
            RunWatchdogPolicy.Verdict.Continue,
            RunWatchdogPolicy.evaluate(
                elapsedRunMs = 5 * 60_000L,
                elapsedSilentMs = 2 * 60_000L,
                idleMs = IDLE,
                maxRunMs = MAX,
            ),
        )
    }

    @Test
    fun `silence reaching idle aborts with IDLE`() {
        // >= semantics: exactly the threshold is already a breach.
        assertEquals(
            RunWatchdogPolicy.Verdict.Abort(RunWatchdogPolicy.Reason.IDLE),
            RunWatchdogPolicy.evaluate(
                elapsedRunMs = 4 * 60_000L,
                elapsedSilentMs = IDLE,
                idleMs = IDLE,
                maxRunMs = MAX,
            ),
        )
        // A long but legitimate run (18 min of streaming) is NOT killed by
        // the old flat 10-min ceiling as long as pulses keep arriving.
        assertEquals(
            RunWatchdogPolicy.Verdict.Continue,
            RunWatchdogPolicy.evaluate(
                elapsedRunMs = 18 * 60_000L,
                elapsedSilentMs = 1_000L,
                idleMs = IDLE,
                maxRunMs = MAX,
            ),
        )
    }

    @Test
    fun `run reaching max aborts with MAX_RUN`() {
        assertEquals(
            RunWatchdogPolicy.Verdict.Abort(RunWatchdogPolicy.Reason.MAX_RUN),
            RunWatchdogPolicy.evaluate(
                elapsedRunMs = MAX,
                elapsedSilentMs = 0L,
                idleMs = IDLE,
                maxRunMs = MAX,
            ),
        )
    }

    @Test
    fun `both breached reports MAX_RUN`() {
        assertEquals(
            RunWatchdogPolicy.Verdict.Abort(RunWatchdogPolicy.Reason.MAX_RUN),
            RunWatchdogPolicy.evaluate(
                elapsedRunMs = MAX + 1,
                elapsedSilentMs = IDLE + 1,
                idleMs = IDLE,
                maxRunMs = MAX,
            ),
        )
    }

    @Test
    fun `null idle means legacy flat ceiling - silence alone never aborts`() {
        assertEquals(
            RunWatchdogPolicy.Verdict.Continue,
            RunWatchdogPolicy.evaluate(
                elapsedRunMs = MAX - 1,
                elapsedSilentMs = Long.MAX_VALUE / 2,
                idleMs = null,
                maxRunMs = MAX,
            ),
        )
        // ...but the max ceiling still fires.
        assertEquals(
            RunWatchdogPolicy.Verdict.Abort(RunWatchdogPolicy.Reason.MAX_RUN),
            RunWatchdogPolicy.evaluate(
                elapsedRunMs = MAX,
                elapsedSilentMs = 0L,
                idleMs = null,
                maxRunMs = MAX,
            ),
        )
    }
}
