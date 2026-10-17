package com.openminis.app.data

/**
 * [T-compact-incremental] Bounds a compaction pass to a cheap, bounded slice
 * of the backlog instead of rewriting everything the marker has accumulated.
 *
 * ## The problem this fixes
 *
 * A long-lived session accumulates a backlog of thousands of messages. When
 * the user finally compacts, the pass covers the WHOLE span since the previous
 * marker: dozens of windows, each a full model call. On a rate-limited relay
 * that is tens of minutes of timeouts, and one failure in the middle wastes
 * everything that already succeeded (the per-window cache softens this, but a
 * single pass still has to grind through the remainder).
 *
 * ## The rule
 *
 * A pass compacts only what fits the budget, and only up to a SAFE boundary:
 * a plain user-text message. A user message can never be one half of a
 * tool_use/tool_result pair, so cutting there cannot orphan a call. If the
 * budget ends inside an assistant/tool run we walk back to the previous user
 * message; if there is none inside the span we leave the anchor untouched and
 * degrade to the previous (full) behaviour — never a half-broken slice.
 *
 * Repeated passes converge: each one removes the oldest chunk, the marker
 * moves forward, and the next pass starts where the previous one stopped.
 * That is what keeps a session from ballooning in the first place.
 *
 * Pure logic so the boundary arithmetic is unit-tested without Android.
 */
object IncrementalSpan {

    /** ~3 windows at the 32K-character packing cap: a 1-3 minute pass. */
    const val DEFAULT_BUDGET_TOKENS: Int = 25_000

    /**
     * @param start first index of the compactable span (inclusive)
     * @param anchor last index the caller wants to compact up to (inclusive)
     * @param tokens per-message token estimate for indices [start]..[anchor]
     * @param isSafeBoundary true when the absolute index may end a pass
     * @param budget token budget for one pass
     * @return the index to use as the pass anchor; [anchor] when the span
     *   already fits or no safe boundary exists (full pass, old behaviour)
     */
    fun bound(
        start: Int,
        anchor: Int,
        tokens: List<Int>,
        isSafeBoundary: (Int) -> Boolean,
        budget: Int = DEFAULT_BUDGET_TOKENS,
    ): Int {
        if (anchor < start || tokens.isEmpty()) return anchor
        var sum = 0L
        var cut = -1
        for (i in tokens.indices) {
            sum += tokens[i].coerceAtLeast(0)
            if (sum > budget) { cut = i - 1; break }
        }
        if (cut < 0) return anchor // entire span fits the budget
        var j = cut
        while (j >= 0) {
            if (isSafeBoundary(start + j)) return start + j
            j--
        }
        return anchor // no safe boundary inside the budget — degrade to full
    }
}
