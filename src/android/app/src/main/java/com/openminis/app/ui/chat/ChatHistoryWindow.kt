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
     * Rows materialised on open. Chosen so a normal conversation opens whole —
     * the window only kicks in on the long sessions that actually hurt — while
     * staying small enough that the parse cost is bounded.
     */
    const val INITIAL_WINDOW = 120

    /** Rows prepended per "load older" step when the user scrolls up. */
    const val OLDER_PAGE = 120

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
}
