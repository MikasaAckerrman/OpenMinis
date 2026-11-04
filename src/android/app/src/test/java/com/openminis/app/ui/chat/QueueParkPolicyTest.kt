package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-recursion-guard] Acceptance criteria A/C from
 * REVIEWS/evidence/20261104/queue-acceptance-spec.md, decision level:
 * a message that races a turn-end PARKS (never delegates to sendMessage —
 * the 23k-frame SOF recursion is unreachable by construction), and the
 * liveness kick is scheduled ONLY for the idle race.
 */
class QueueParkPolicyTest {

    @Test
    fun `turn running - park without kick (tail drain delivers)`() {
        val d = QueueParkPolicy.decide(streaming = true, sessionLockHeld = false)
        assertTrue(d.park)
        assertFalse(d.scheduleLivenessKick)
    }

    @Test
    fun `cancel window - park without kick (winding tail holds the lock)`() {
        // Stop force-released the slot (flag false) while the dying turn's
        // tail still holds the session lock: delegating here WAS the
        // 23k-frame StackOverflow. Park; the tail's last-breath kick delivers.
        val d = QueueParkPolicy.decide(streaming = false, sessionLockHeld = true)
        assertTrue(d.park)
        assertFalse(d.scheduleLivenessKick)
    }

    @Test
    fun `idle race - park with liveness kick`() {
        // Scenario A: the last tail-kick already inspected an empty queue.
        val d = QueueParkPolicy.decide(streaming = false, sessionLockHeld = false)
        assertTrue(d.park)
        assertTrue("idle race requires the park's own delivery kick", d.scheduleLivenessKick)
    }

    @Test
    fun `park is unconditional in every state`() {
        for (streaming in listOf(true, false)) {
            for (lock in listOf(true, false)) {
                assertTrue(
                    "park must never depend on state (no delegation path)",
                    QueueParkPolicy.decide(streaming, lock).park,
                )
            }
        }
    }
}
