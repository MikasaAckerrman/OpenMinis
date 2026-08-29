package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-window-safe-cutoff] The regression these tests exist for: on a windowed
 * session the ordinal-based cutoff resolved to a row from days earlier and the
 * retry deleted everything after it.
 */
class MessageCutoffTest {

    private fun rows(vararg ids: String): List<MessageCutoff.Row> =
        ids.mapIndexed { i, id -> MessageCutoff.Row(id, i) }

    @Test
    fun `retry keeps the tapped row and drops what follows`() {
        val r = rows("a", "b", "c", "d")
        val keep = MessageCutoff.retryKeepCount(listOf("b"), r)
        assertEquals(2, keep)   // sort_order 1 kept, >= 2 deleted
    }

    @Test
    fun `edit drops the tapped row too`() {
        val r = rows("a", "b", "c", "d")
        assertEquals(1, MessageCutoff.editKeepCount(listOf("b"), r))
    }

    @Test
    fun `merged assistant bubble cuts after its LAST row`() {
        // One bubble built from three consecutive assistant rows.
        val r = rows("u", "a1", "a2", "a3", "next")
        val keep = MessageCutoff.retryKeepCount(listOf("a1", "a2", "a3"), r)
        assertEquals(4, keep)   // none of a1..a3 may be deleted
    }

    @Test
    fun `merged bubble edit cuts before its FIRST row`() {
        val r = rows("u", "a1", "a2", "a3", "next")
        assertEquals(1, MessageCutoff.editKeepCount(listOf("a1", "a2", "a3"), r))
    }

    /**
     * The actual bug. Window shows the last 3 rows of a 700-row session; the
     * tapped bubble is the FIRST visible user bubble, so the old ordinal path
     * computed "user turn #0" and cut at the top of the session.
     */
    @Test
    fun `windowed session cuts at the real row, not at the ordinal`() {
        val all = (0 until 700).map { MessageCutoff.Row("m$it", it) }
        val tapped = listOf("m697")            // visible bubble, ordinal 0 in-window
        assertEquals(698, MessageCutoff.retryKeepCount(tapped, all))
        assertTrue(MessageCutoff.isPlausible(698, 700))
    }

    @Test
    fun `unresolvable bubble yields null so the caller can refuse`() {
        assertNull(MessageCutoff.retryKeepCount(listOf("ghost"), rows("a", "b")))
        assertNull(MessageCutoff.editKeepCount(listOf("ghost"), rows("a", "b")))
        assertNull(MessageCutoff.retryKeepCount(emptyList(), rows("a", "b")))
        assertNull(MessageCutoff.retryKeepCount(listOf("a"), emptyList()))
    }

    @Test
    fun `bubble id is used when sourceDbIds is empty`() {
        val ids = MessageCutoff.candidateIds(emptyList(), "live-row-id")
        assertEquals(listOf("live-row-id"), ids)
        assertEquals(1, MessageCutoff.retryKeepCount(ids, rows("live-row-id", "x")))
    }

    @Test
    fun `candidateIds prefers sourceDbIds and never duplicates`() {
        val ids = MessageCutoff.candidateIds(listOf("r1", "r2", ""), "r1")
        assertEquals(listOf("r1", "r2"), ids)
    }

    @Test
    fun `plausibility rejects a cut that would wipe the session`() {
        assertFalse(MessageCutoff.isPlausible(keepCount = 1, totalRows = 700))
        assertFalse(MessageCutoff.isPlausible(keepCount = -1, totalRows = 10))
        assertFalse(MessageCutoff.isPlausible(keepCount = 11, totalRows = 10))
        assertTrue(MessageCutoff.isPlausible(keepCount = 0, totalRows = 0))
    }

    @Test
    fun `plausibility allows a long tool-heavy turn`() {
        // 300 rows of agent-loop traffic in one turn is normal, not an anchor bug.
        assertTrue(MessageCutoff.isPlausible(keepCount = 400, totalRows = 700))
    }

    // ─── [T-truncation-chokepoint] repository-level guard ─────────────────

    private fun refused(keep: Int, total: Int): Boolean =
        MessageCutoff.checkTruncation(keep, total) is MessageCutoff.Verdict.Refuse

    @Test
    fun `chokepoint refuses the incident shape`() {
        // The ordinal bug: keepCount=1 on a ~700-row session.
        assertTrue(refused(keep = 1, total = 700))
    }

    @Test
    fun `chokepoint refuses a negative keepCount outright`() {
        // sort_order >= -1 matches every row, so an unresolved anchor that
        // leaked through as -1 is a whole-session delete.
        assertTrue(refused(keep = -1, total = 700))
        assertTrue(refused(keep = -1, total = 3))
    }

    @Test
    fun `chokepoint exempts short sessions`() {
        // Retrying turn 2 of a 4-turn chat legitimately drops most of it.
        assertFalse(refused(keep = 1, total = 4))
        assertFalse(refused(keep = 2, total = 49))
    }

    @Test
    fun `chokepoint allows normal truncation on a long session`() {
        assertFalse(refused(keep = 690, total = 700))   // one turn's tail
        assertFalse(refused(keep = 400, total = 700))   // tool-heavy turn
        assertFalse(refused(keep = 71, total = 700))    // 90% exactly — at the line
    }

    @Test
    fun `chokepoint allows a no-op and an out-of-range keepCount`() {
        // keepCount >= totalRows deletes nothing; never refuse a no-op.
        assertFalse(refused(keep = 700, total = 700))
        assertFalse(refused(keep = 900, total = 700))
    }

    @Test
    fun `chokepoint reason names the numbers`() {
        val v = MessageCutoff.checkTruncation(1, 700)
        assertTrue(v is MessageCutoff.Verdict.Refuse)
        val reason = (v as MessageCutoff.Verdict.Refuse).reason
        assertTrue(reason.contains("699"))
        assertTrue(reason.contains("700"))
    }
}
