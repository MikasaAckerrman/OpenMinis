package com.openminis.app.data

import com.openminis.app.ui.chat.assistantTurnTimingLabel
import com.openminis.app.ui.chat.formatStepDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-msg-timestamps] The assistant-header timing chip mimics IDE agents:
 * "HH:mm" at turn start, "HH:mm · <dur>" once the turn ends. The branching is
 * extracted into [assistantTurnTimingLabel] so it is testable without a
 * renderer. The wall-clock half is locale/timezone dependent, so these assert
 * on the duration suffix and the null/format decisions rather than the exact
 * clock string.
 */
class MessageTimestampTest {

    @Test
    fun `legacy row with no timestamp hides the chip`() {
        // createdAtMs <= 0 is the "unknown" sentinel for rows that predate the
        // feature — the chip must be hidden, not rendered as an epoch-0 time.
        assertNull(assistantTurnTimingLabel(createdAtMs = 0L, finishedAtMs = null))
        assertNull(assistantTurnTimingLabel(createdAtMs = -1L, finishedAtMs = 999L))
    }

    @Test
    fun `in-flight turn shows only the start time`() {
        // finishedAtMs null → no duration yet; label must be the bare start
        // time with no " · " separator.
        val label = assistantTurnTimingLabel(createdAtMs = 1_000_000L, finishedAtMs = null)
        assertTrue("expected a non-null start-only label", label != null)
        assertTrue("start-only label must not contain a duration separator", !label!!.contains("·"))
    }

    @Test
    fun `completed turn appends the duration`() {
        // 4s turn: 1_000_000 → 1_004_000 ms.
        val label = assistantTurnTimingLabel(createdAtMs = 1_000_000L, finishedAtMs = 1_004_000L)
        assertTrue(label!!.contains("·"))
        assertTrue("expected 4s duration suffix", label.endsWith("4s"))
    }

    @Test
    fun `restored row with zero duration falls back to start time`() {
        // DB rows persist created_at at turn END, so finished-created rounds to
        // 0s — meaningless, so we drop the duration and show only the time.
        val label = assistantTurnTimingLabel(createdAtMs = 1_000_000L, finishedAtMs = 1_000_400L)
        assertTrue("sub-second duration must not render a separator", !label!!.contains("·"))
    }

    @Test
    fun `negative duration is treated as unfinished`() {
        // A clock skew where finished < created must never produce "· -3s".
        val label = assistantTurnTimingLabel(createdAtMs = 1_000_000L, finishedAtMs = 990_000L)
        assertTrue(!label!!.contains("·"))
    }

    @Test
    fun `duration formatter matches the turn chip contract`() {
        // Guards the exact strings the chip embeds.
        assertEquals("4s", formatStepDuration(4L, stillRunning = false))
        assertEquals("2m30s", formatStepDuration(150L, stillRunning = false))
        assertEquals("1h12m", formatStepDuration(4320L, stillRunning = false))
    }
}
