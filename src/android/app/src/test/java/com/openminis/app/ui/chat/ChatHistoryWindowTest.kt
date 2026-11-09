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

    // ─── [B25] preserveInMemoryTail — the window re-publish must not wipe
    // everything that landed since the early gate ───────────────────────────

    private fun msg(
        id: String,
        role: String = "user",
        isQueued: Boolean = false,
        queuedPromptId: String? = null,
        isStreaming: Boolean = false,
        isAwaitingModelResponse: Boolean = false,
        toolBlocks: List<AssistantBlock> = emptyList(),
    ) = ChatMessage(
        id = id,
        role = role,
        content = "",
        isQueued = isQueued,
        queuedPromptId = queuedPromptId,
        isStreaming = isStreaming,
        isAwaitingModelResponse = isAwaitingModelResponse,
        toolBlocks = toolBlocks,
    )

    @Test
    fun `queued pending streaming awaiting and system notice survive the republish`() {
        val current = listOf(
            msg("queued_msg_p1", isQueued = true, queuedPromptId = "p1"),
            msg("pending_abc"),
            msg("assistant_live", role = "assistant", isStreaming = true),
            msg("assistant_wait", role = "assistant", isAwaitingModelResponse = true),
            msg("notice_x", role = "system", toolBlocks = listOf(AssistantBlock(id = "b", kind = "text"))),
        )
        val tail = ChatHistoryWindow.preserveInMemoryTail(current, installedDbIds = setOf("db1"))
        assertEquals(
            listOf("queued_msg_p1", "pending_abc", "assistant_live", "assistant_wait", "notice_x"),
            tail.map { it.id },
        )
    }

    @Test
    fun `db-backed rows that the window installs are dropped (no dupes)`() {
        // A row whose id IS in the installed window is the window's business;
        // rescuing it would duplicate it.
        val current = listOf(
            msg("db1"),
            msg("db2"),
            msg("queued_msg_p1", isQueued = true, queuedPromptId = "p1"),
        )
        val tail = ChatHistoryWindow.preserveInMemoryTail(current, installedDbIds = setOf("db1", "db2"))
        assertEquals(listOf("queued_msg_p1"), tail.map { it.id })
    }

    @Test
    fun `db-backed rows absent from the window are NOT rescued (no ghosts)`() {
        // The window is the authority on what the DB holds; a plain user row
        // missing from it is stale data, not an ephemeral bubble.
        val current = listOf(
            msg("stale-db-row"),
            msg("db1"),
        )
        val tail = ChatHistoryWindow.preserveInMemoryTail(current, installedDbIds = setOf("db1"))
        assertEquals(emptyList<String>(), tail.map { it.id })
    }

    @Test
    fun `compact marker system row is not rescued (graying recreates it)`() {
        // The marker row is identified by toolName == "compact" (the same
        // discriminator the notices filter has always used) — kind alone
        // is not the marker signature.
        val current = listOf(
            msg(
                "compact-marker", role = "system",
                toolBlocks = listOf(AssistantBlock(id = "b", kind = "compact", toolName = "compact")),
            ),
            msg("notice_x", role = "system", toolBlocks = listOf(AssistantBlock(id = "b", kind = "text"))),
        )
        val tail = ChatHistoryWindow.preserveInMemoryTail(current, installedDbIds = emptySet())
        assertEquals(listOf("notice_x"), tail.map { it.id })
    }

    @Test
    fun `system row without toolBlocks counts as a notice`() {
        // appendSystemInfo rows may carry no tool block at all; firstOrNull
        // is null so the != compact predicate holds.
        val current = listOf(msg("bare-notice", role = "system"))
        val tail = ChatHistoryWindow.preserveInMemoryTail(current, installedDbIds = emptySet())
        assertEquals(listOf("bare-notice"), tail.map { it.id })
    }

    @Test
    fun `nothing to rescue returns an empty tail`() {
        val current = listOf(msg("db1"), msg("db2"))
        val tail = ChatHistoryWindow.preserveInMemoryTail(current, installedDbIds = setOf("db1", "db2"))
        assertEquals(emptyList<ChatMessage>(), tail)
    }

    @Test
    fun `active stream id is rescued even with flickered flags`() {
        // [B43-background-answer] The live turn's placeholder is not in the
        // DB yet; if isStreaming/isAwaitingModelResponse flicker between the
        // wipe's read and the stream's next write, flag-based rescue drops
        // it and every later updateAssistantMessage maps onto nothing —
        // "only my prompt remains" until re-entry. The overlay key set
        // rescues it unconditionally.
        val current = listOf(
            msg("user1"),
            msg("assistant_pre_42"), // flags all false — the flicker
        )
        val tail = ChatHistoryWindow.preserveInMemoryTail(
            current,
            installedDbIds = setOf("user1"),
            activeStreamIds = setOf("assistant_pre_42"),
        )
        assertEquals(listOf("assistant_pre_42"), tail.map { it.id })
    }

    @Test
    fun `active stream rescue does not resurrect db-window ghosts`() {
        // The window stays the authority on what the DB holds: an id in the
        // installed window is never rescued, stream set or not.
        val current = listOf(msg("db1"), msg("assistant_pre_42"))
        val tail = ChatHistoryWindow.preserveInMemoryTail(
            current,
            installedDbIds = setOf("db1", "assistant_pre_42"),
            activeStreamIds = setOf("assistant_pre_42"),
        )
        assertEquals(emptyList<ChatMessage>(), tail)
    }
}
