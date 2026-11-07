package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryWindowTest {

    // ─── [B24] the window step itself is pinned — it IS the perf fix ────────

    @Test
    fun `window step is 40 rows (B24 - was 120)`() {
        assertEquals(40, ChatHistoryWindow.INITIAL_WINDOW)
        assertEquals(40, ChatHistoryWindow.OLDER_PAGE)
    }

    @Test
    fun `short session loads whole and reports no older rows`() {
        val w = ChatHistoryWindow.initial(totalRows = 30)
        assertEquals(0, w.fromIndex)
        assertEquals(30, w.count)
        assertFalse(w.hasOlder)
    }

    @Test
    fun `window sits at the END of a long session`() {
        // A chat opens at the bottom: the first screen must be the NEWEST rows.
        val w = ChatHistoryWindow.initial(totalRows = 723)
        assertEquals(683, w.fromIndex)
        assertEquals(40, w.count)
        assertTrue(w.hasOlder)
        assertEquals(723, w.fromIndex + w.count)
    }

    @Test
    fun `exactly-window-sized session has nothing older`() {
        val w = ChatHistoryWindow.initial(totalRows = ChatHistoryWindow.INITIAL_WINDOW)
        assertEquals(0, w.fromIndex)
        assertEquals(ChatHistoryWindow.INITIAL_WINDOW, w.count)
        assertFalse(w.hasOlder)
    }

    @Test
    fun `empty session yields an empty window`() {
        val w = ChatHistoryWindow.initial(totalRows = 0)
        assertEquals(0, w.count)
        assertFalse(w.hasOlder)
    }

    @Test
    fun `explicit windowSize still parameterizes the math`() {
        // The constants are defaults, not magic: a caller may ask for a
        // bigger window and the slice math must generalize.
        val w = ChatHistoryWindow.initial(totalRows = 723, windowSize = 120)
        assertEquals(603, w.fromIndex)
        assertEquals(120, w.count)
        assertTrue(w.hasOlder)
    }

    @Test
    fun `scrolling up walks backwards without gaps or overlap`() {
        val initial = ChatHistoryWindow.initial(totalRows = 723)
        val first = ChatHistoryWindow.older(initial.fromIndex)
        // The prepended slice must end exactly where the loaded window begins.
        assertEquals(initial.fromIndex, first.fromIndex + first.count)
        assertEquals(643, first.fromIndex)
        assertTrue(first.hasOlder)
    }

    @Test
    fun `last older page is clamped to the head and stops`() {
        val w = ChatHistoryWindow.older(currentFromIndex = 30)
        assertEquals(0, w.fromIndex)
        assertEquals(30, w.count)
        assertFalse(w.hasOlder)
    }

    @Test
    fun `older at the head is a no-op`() {
        val w = ChatHistoryWindow.older(currentFromIndex = 0)
        assertEquals(0, w.count)
        assertFalse(w.hasOlder)
    }

    @Test
    fun `walking every 40-row page covers the history exactly once`() {
        val total = 723
        val initial = ChatHistoryWindow.initial(total)
        var covered = initial.count
        var from = initial.fromIndex
        var guard = 0
        while (from > 0) {
            if (guard++ > 100) error("older() must terminate")
            val page = ChatHistoryWindow.older(from)
            assertEquals("page must abut the loaded window", from, page.fromIndex + page.count)
            covered += page.count
            from = page.fromIndex
        }
        assertEquals(total, covered)
    }

    @Test
    fun `reported 1443-row session opens from the newest 40 rows`() {
        val w = ChatHistoryWindow.initial(totalRows = 1443)
        assertEquals(1403, w.fromIndex)
        assertEquals(40, w.count)
        assertTrue(w.hasOlder)
        assertEquals(1443, w.fromIndex + w.count)
    }

    @Test
    fun `truncated display window still forces a full LLM history`() {
        // Sending with a truncated history would silently drop context — a
        // correctness bug, not a perf trade-off.
        assertTrue(ChatHistoryWindow.llmHistoryNeedsFullLoad(hasOlder = true))
        assertFalse(ChatHistoryWindow.llmHistoryNeedsFullLoad(hasOlder = false))
    }

    // ─── [B26] mergeOlder — the merge that replaced the snapshot discard ────

    private data class M(val id: String, val tag: String = "")

    @Test
    fun `older rows are prepended in order without touching current`() {
        val current = listOf(M("m5"), M("m6"))
        val older = listOf(M("m3"), M("m4"))
        assertEquals(listOf(M("m3"), M("m4"), M("m5"), M("m6")), ChatHistoryWindow.mergeOlder(current, older) { it.id })
    }

    @Test
    fun `rows already present in current are dropped (no duplicates)`() {
        // Overlap: another writer installed some of the same rows while the
        // page was being read. They must not appear twice.
        val current = listOf(M("m4", "installed-by-other-writer"), M("m5"))
        val older = listOf(M("m3"), M("m4", "stale-copy"))
        assertEquals(
            listOf(M("m3"), M("m4", "installed-by-other-writer"), M("m5")),
            ChatHistoryWindow.mergeOlder(current, older) { it.id },
        )
    }

    @Test
    fun `fully overlapping page is a no-op returning the same list`() {
        val current = listOf(M("m1"), M("m2"))
        val older = listOf(M("m1"), M("m2"))
        assertSame(current, ChatHistoryWindow.mergeOlder(current, older) { it.id })
    }

    @Test
    fun `empty older page is a no-op returning the same list`() {
        val current = listOf(M("m1"))
        assertSame(current, ChatHistoryWindow.mergeOlder(current, emptyList()) { it.id })
    }

    @Test
    fun `merge onto an empty current installs the whole page`() {
        val older = listOf(M("m1"), M("m2"))
        assertEquals(older, ChatHistoryWindow.mergeOlder(emptyList(), older) { it.id })
    }

    @Test
    fun `ephemeral system rows in current do not block db rows`() {
        // In-memory notices (role=system) ride in the same list; their ids
        // never collide with DB-backed rows, so nothing is dropped.
        val current = listOf(M("notice-1"), M("m6"))
        val older = listOf(M("m5"))
        assertEquals(
            listOf(M("m5"), M("notice-1"), M("m6")),
            ChatHistoryWindow.mergeOlder(current, older) { it.id },
        )
    }
}
