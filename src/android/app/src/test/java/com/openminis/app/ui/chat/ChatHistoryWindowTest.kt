package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryWindowTest {

    @Test
    fun `short session loads whole and reports no older rows`() {
        val w = ChatHistoryWindow.initial(totalRows = 40, windowSize = 120)
        assertEquals(0, w.fromIndex)
        assertEquals(40, w.count)
        assertFalse(w.hasOlder)
    }

    @Test
    fun `window sits at the END of a long session`() {
        // A chat opens at the bottom: the first screen must be the NEWEST rows.
        val w = ChatHistoryWindow.initial(totalRows = 723, windowSize = 120)
        assertEquals(603, w.fromIndex)
        assertEquals(120, w.count)
        assertTrue(w.hasOlder)
        assertEquals(723, w.fromIndex + w.count)
    }

    @Test
    fun `exactly-window-sized session has nothing older`() {
        val w = ChatHistoryWindow.initial(totalRows = 120, windowSize = 120)
        assertEquals(0, w.fromIndex)
        assertEquals(120, w.count)
        assertFalse(w.hasOlder)
    }

    @Test
    fun `empty session yields an empty window`() {
        val w = ChatHistoryWindow.initial(totalRows = 0, windowSize = 120)
        assertEquals(0, w.count)
        assertFalse(w.hasOlder)
    }

    @Test
    fun `scrolling up walks backwards without gaps or overlap`() {
        val initial = ChatHistoryWindow.initial(totalRows = 723, windowSize = 120)
        val first = ChatHistoryWindow.older(initial.fromIndex, pageSize = 120)
        // The prepended slice must end exactly where the loaded window begins.
        assertEquals(initial.fromIndex, first.fromIndex + first.count)
        assertEquals(483, first.fromIndex)
        assertTrue(first.hasOlder)
    }

    @Test
    fun `last older page is clamped to the head and stops`() {
        val w = ChatHistoryWindow.older(currentFromIndex = 40, pageSize = 120)
        assertEquals(0, w.fromIndex)
        assertEquals(40, w.count)
        assertFalse(w.hasOlder)
    }

    @Test
    fun `older at the head is a no-op`() {
        val w = ChatHistoryWindow.older(currentFromIndex = 0, pageSize = 120)
        assertEquals(0, w.count)
        assertFalse(w.hasOlder)
    }

    @Test
    fun `walking every page covers the history exactly once`() {
        val total = 723
        val initial = ChatHistoryWindow.initial(total, windowSize = 120)
        var covered = initial.count
        var from = initial.fromIndex
        var guard = 0
        while (from > 0) {
            if (guard++ > 100) error("older() must terminate")
            val page = ChatHistoryWindow.older(from, pageSize = 120)
            assertEquals("page must abut the loaded window", from, page.fromIndex + page.count)
            covered += page.count
            from = page.fromIndex
        }
        assertEquals(total, covered)
    }

    @Test
    fun `reported 1443-row session opens from the newest 120 rows`() {
        val w = ChatHistoryWindow.initial(totalRows = 1443)
        assertEquals(1323, w.fromIndex)
        assertEquals(120, w.count)
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
}
