package com.openminis.app.ui.chat

/**
 * [T-android-chat-open-full-history] Pure policy for how much of a session's
 * history is materialised when the chat screen opens.
 *
 * Why this exists: `loadSession` read EVERY row of a session and then parsed
 * each row's `partsJson` three separate times — once to build the tool-result
 * map, once per row for the UI model, once per row for the LLM history. On a
 * 723-message session that is thousands of JSON parses on the open path, which
 * is what the earlier GC-storm incident (405 messages, 58 s frame stall) was
 * already about; the session has since roughly doubled.
 *
 * The policy is deliberately trivial and testable: decide a window over the
 * ordered row list, and never split it in a way that loses the tail (the newest
 * messages are the ones the user is looking at, and the ones the next LLM turn
 * needs).
 */
object ChatHistoryWindow {

    /**
     * Rows materialised on open. [B24] Was 120 — chosen when a "message"
     * meant a couple of text lines. In practice one agent turn unfolds into
     * thinking + a tool_use card (often a several-hundred-line file) + the
     * tool result + markdown body ≈ 5–10 composable nodes, so 120 rows
     * materialised 600–1200 nodes that Compose held and re-laid-out on every
     * scroll. 40 keeps the open burst bounded (~3× cheaper cold start) while
     * still covering a screenful-plus of context; anything older rides the
     * [OLDER_PAGE] pagination.
     */
    const val INITIAL_WINDOW = 40

    /**
     * Rows prepended per "load older" step when the user scrolls up.
     * [B24] Matched to [INITIAL_WINDOW]: one page, one parse burst, one
     * layout pass — 120-row pages made every pagination click as expensive
     * as the open itself.
     */
    const val OLDER_PAGE = 40

    data class Window(
        /** Index of the first row to materialise, inclusive. */
        val fromIndex: Int,
        /** Number of rows to materialise. */
        val count: Int,
        /** True when rows exist before [fromIndex] and can still be loaded. */
        val hasOlder: Boolean,
    )

    /**
     * Window for a fresh open: the newest [windowSize] rows.
     *
     * Anchored at the END, not the start — a chat opens at the bottom, so the
     * first screen must come from the newest rows. Loading the oldest N would
     * cost the same parse and show the wrong thing.
     */
    fun initial(totalRows: Int, windowSize: Int = INITIAL_WINDOW): Window {
        require(windowSize > 0) { "windowSize must be > 0" }
        if (totalRows <= windowSize) {
            return Window(fromIndex = 0, count = totalRows, hasOlder = false)
        }
        val from = totalRows - windowSize
        return Window(fromIndex = from, count = windowSize, hasOlder = from > 0)
    }

    /**
     * Window extension when the user scrolls past the top of what is loaded.
     * Returns the slice of NEW rows to prepend; [Window.count] is 0 when the
     * head of the history is already materialised.
     */
    fun older(
        currentFromIndex: Int,
        pageSize: Int = OLDER_PAGE,
    ): Window {
        require(pageSize > 0) { "pageSize must be > 0" }
        if (currentFromIndex <= 0) return Window(fromIndex = 0, count = 0, hasOlder = false)
        val from = (currentFromIndex - pageSize).coerceAtLeast(0)
        return Window(fromIndex = from, count = currentFromIndex - from, hasOlder = from > 0)
    }

    /**
     * Whether the LLM history needs the FULL row set rather than the window.
     *
     * The UI can lazily fill in older bubbles, but a model request cannot: an
     * outgoing turn built from a truncated history would silently forget the
     * earlier conversation, which is a correctness bug, not a perf trade-off.
     * So the window governs what is parsed for display on open; the full
     * history is built once, off the main thread, before the first send.
     */
    fun llmHistoryNeedsFullLoad(hasOlder: Boolean): Boolean = hasOlder

    /**
     * [B26] Idempotent prepend of freshly loaded older rows onto the current
     * display list. Replaces the old `_messages.value !== expectedSnapshot`
     * discard: the snapshot check threw away the whole page whenever ANY
     * list write landed between the IO read and the Main-thread install
     * (a streaming tick, a background notice) — with no retry, no error, and
     * no window-start update, which is exactly the "button does nothing"
     * report. Merging reads the list AS IT IS at install time, so
     * concurrent writers (whose every mutation rebuilds from the current
     * value) compose with the prepend instead of racing it.
     *
     * Duplicate ids (an overlap between the page and what other writers
     * installed meanwhile) are dropped from [older]; current is never
     * touched. Order: older rows keep their relative order and land before
     * the current list (chronological head).
     */
    fun <T> mergeOlder(
        current: List<T>,
        older: List<T>,
        idOf: (T) -> String,
    ): List<T> {
        if (older.isEmpty()) return current
        val existingIds = current.mapTo(HashSet()) { idOf(it) }
        val freshOlder = if (existingIds.isEmpty()) {
            older
        } else {
            older.filter { idOf(it) !in existingIds }
        }
        if (freshOlder.isEmpty()) return current
        return freshOlder + current
    }

    /**
     * [B25] The in-memory tail that must SURVIVE a window re-publish.
     *
     * `loadSession` publishes the DB window twice — at the early gate and
     * again once the full LLM history has parsed (possibly re-grayed). The
     * installed window is built ONLY from DB rows, so a bare assign wiped
     * everything that had landed in the list since the first publish:
     * restored queued bubbles, user messages sent before the gate opened,
     * pending/streaming rows and system notices — the "messages disappear
     * from the chat" report. A row is tail-rescuable when it does not come
     * from the installed DB window AND it is ephemeral (queued / pending /
     * streaming / awaiting / a non-compact system notice): DB-backed rows
     * absent from the window are NOT rescued — the window is the authority
     * on what the DB currently holds, and stale ghosts must not resurrect.
     * Compact markers are excluded too: the divider is (re)created by the
     * graying pass over the installed window, never carried from the old
     * list.
     *
     * Returns ONLY the rescued rows (caller appends them after the window) —
     * emptyList when there is nothing to rescue.
     */
    fun preserveInMemoryTail(
        current: List<ChatMessage>,
        installedDbIds: Set<String>,
    ): List<ChatMessage> = current.filter { m ->
        m.id !in installedDbIds && (
            m.isQueued || m.queuedPromptId != null ||
                m.id.startsWith("queued_msg_") || m.id.startsWith("pending_") ||
                m.isStreaming || m.isAwaitingModelResponse ||
                (m.role == "system" && m.toolBlocks.firstOrNull()?.toolName != "compact")
            )
    }
}
