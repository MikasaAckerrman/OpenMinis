package com.openminis.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B27] Pure decision surfaces of the background-crash fix: the FGS
 * zombie-restart rule (null intent + no sessions must NOT go foreground)
 * and the overlay attach guard (a dead window token must degrade to a
 * detached overlay, never an uncaught crash).
 */
class AgentFgsRulesTest {

    @Test
    fun `null intent restart with no sessions stops the zombie`() {
        assertTrue(AgentFgsRules.shouldStopOnZombieRestart(hasIntent = false, activeSessionCount = 0))
    }

    @Test
    fun `null intent restart with a live session keeps the service`() {
        assertFalse(AgentFgsRules.shouldStopOnZombieRestart(hasIntent = false, activeSessionCount = 1))
    }

    @Test
    fun `explicit intents never count as zombie restarts`() {
        // Session starts / tool ticks carry real intents; ACTION_STOP is
        // handled by its own branch before this rule runs.
        assertFalse(AgentFgsRules.shouldStopOnZombieRestart(hasIntent = true, activeSessionCount = 0))
        assertFalse(AgentFgsRules.shouldStopOnZombieRestart(hasIntent = true, activeSessionCount = 2))
    }

    @Test
    fun `negative session count is treated as zero (defensive)`() {
        assertTrue(AgentFgsRules.shouldStopOnZombieRestart(hasIntent = false, activeSessionCount = -1))
    }
}

class OverlayAttachGuardTest {

    @Test
    fun `successful addView reports true`() {
        var called = 0
        val ok = OverlayAttachGuard.addViewSafely("test") { called++ }
        assertTrue(ok)
        assertEquals(1, called)
    }

    @Test
    fun `dead window token degrades to false instead of crashing`() {
        // The exact production shape: BadTokenException from WindowManager
        // inside a background service — must never escape as an uncaught
        // crash, and must not be swallowed silently either (logged inside).
        val ok = OverlayAttachGuard.addViewSafely("test") {
            throw android.view.WindowManager.BadTokenException("token android.os.BinderProxy@dead is not valid")
        }
        assertFalse(ok)
    }

    @Test
    fun `any throwable family degrades the same way`() {
        val ok = OverlayAttachGuard.addViewSafely("test") {
            throw IllegalStateException("InvalidDisplayException-shaped failure")
        }
        assertFalse(ok)
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
