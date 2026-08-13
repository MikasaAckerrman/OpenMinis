package com.openminis.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-context-autocompact] Decision matrix for the auto-compact trigger.
 * The trigger replaces the old "warn and hope the user runs /compact"
 * behaviour — it must fire on NEEDS_COMPACT, never on OK/EXHAUSTED,
 * never stack on an in-flight compact, and respect the cooldown.
 */
class ContextAutoCompactTest {

    private val now = 1_000_000_000L

    @Test
    fun `fires on needs-compact when idle and enabled`() {
        assertTrue(
            ContextAutoCompact.shouldTrigger(
                check = ContextPolicy.CheckResult.NEEDS_COMPACT,
                enabled = true,
                isCompacting = false,
                lastRunAtMs = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun `never fires on OK or EXHAUSTED`() {
        for (check in listOf(ContextPolicy.CheckResult.OK, ContextPolicy.CheckResult.EXHAUSTED)) {
            assertFalse(
                ContextAutoCompact.shouldTrigger(check, true, false, 0L, now)
            )
        }
    }

    @Test
    fun `respects the master toggle`() {
        assertFalse(
            ContextAutoCompact.shouldTrigger(
                ContextPolicy.CheckResult.NEEDS_COMPACT, false, false, 0L, now,
            )
        )
    }

    @Test
    fun `never stacks on an in-flight compact`() {
        assertFalse(
            ContextAutoCompact.shouldTrigger(
                ContextPolicy.CheckResult.NEEDS_COMPACT, true, true, 0L, now,
            )
        )
    }

    @Test
    fun `cooldown blocks immediate retrigger`() {
        val lastRun = now - ContextAutoCompact.COOLDOWN_MS + 1_000
        assertFalse(
            ContextAutoCompact.shouldTrigger(
                ContextPolicy.CheckResult.NEEDS_COMPACT, true, false, lastRun, now,
            )
        )
    }

    @Test
    fun `cooldown expiry re-arms the trigger`() {
        val lastRun = now - ContextAutoCompact.COOLDOWN_MS
        assertTrue(
            ContextAutoCompact.shouldTrigger(
                ContextPolicy.CheckResult.NEEDS_COMPACT, true, false, lastRun, now,
            )
        )
    }
}
