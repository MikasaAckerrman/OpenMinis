package com.openminis.app.data

import com.openminis.app.ui.chat.assistantTurnFinishedLabel
import com.openminis.app.ui.chat.formatStepDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-msg-timestamps] The assistant FINISH stamp shown under the message mimics
 * IDE agents: it reads the wall-clock time the turn COMPLETED, plus the elapsed
 * duration when a real live-turn measurement exists. The branching is extracted
 * into [assistantTurnFinishedLabel] so it is testable without a renderer. The
 * wall-clock half is locale/timezone dependent, so these assert on the duration
 * suffix and the null/format decisions rather than the exact clock string.
 */
class MessageTimestampTest {

    @Test
    fun `unknown finish time hides the stamp`() {
        // finishedAtMs <= 0 is the "unknown" sentinel — hide, don't render an
        // epoch-0 time.
        assertNull(assistantTurnFinishedLabel(createdAtMs = 1_000_000L, finishedAtMs = 0L))
        assertNull(assistantTurnFinishedLabel(createdAtMs = 0L, finishedAtMs = -1L))
    }

    @Test
    fun `completed live turn appends the duration`() {
        // 4s turn: created 1_000_000 → finished 1_004_000 ms. Stamp is the
        // finish time plus "· 4s".
        val label = assistantTurnFinishedLabel(createdAtMs = 1_000_000L, finishedAtMs = 1_004_000L)
        assertTrue(label!!.contains("·"))
        assertTrue("expected 4s duration suffix", label.endsWith("4s"))
    }

    @Test
    fun `restored row with zero duration shows only the finish time`() {
        // DB rows persist created_at at turn END, so finished-created rounds to
        // 0s — meaningless, so we drop the duration and show only the time.
        val label = assistantTurnFinishedLabel(createdAtMs = 1_000_000L, finishedAtMs = 1_000_400L)
        assertTrue("sub-second duration must not render a separator", !label!!.contains("·"))
    }

    @Test
    fun `missing start time shows only the finish time`() {
        // createdAtMs <= 0 (legacy row) → we still know when it finished, so
        // show the finish clock without a bogus duration.
        val label = assistantTurnFinishedLabel(createdAtMs = 0L, finishedAtMs = 1_004_000L)
        assertTrue(label != null && !label.contains("·"))
    }

    @Test
    fun `clock skew is treated as unknown duration`() {
        // finished < created must never produce "· -3s".
        val label = assistantTurnFinishedLabel(createdAtMs = 1_000_000L, finishedAtMs = 990_000L)
        assertTrue(!label!!.contains("·"))
    }

    @Test
    fun `duration formatter matches the stamp contract`() {
        // Guards the exact strings the stamp embeds.
        assertEquals("4s", formatStepDuration(4L, stillRunning = false))
        assertEquals("2m30s", formatStepDuration(150L, stillRunning = false))
        assertEquals("1h12m", formatStepDuration(4320L, stillRunning = false))
    }
}
