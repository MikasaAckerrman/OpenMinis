package com.openminis.app.data

/**
 * [T-compact-divider-count] How many messages does the compaction divider
 * claim were folded into the summary?
 *
 * ## The bug this replaces
 *
 * The count was `messages[0 until insertIdx].count { role != "system" }` — UI
 * bubbles above the divider **in the currently materialised list**. That was
 * right when the list was the whole session. Since
 * [com.openminis.app.ui.chat.ChatHistoryWindow] a long chat opens with a window
 * over the newest 120 rows, so if the anchor lands near the start of that
 * window the divider counts only the two or three bubbles that happen to be
 * loaded above it and announces "2 messages compacted" for a summary built from
 * several hundred. The user reads it as the app having compacted nothing.
 *
 * ## The fix
 *
 * The window truncates what we can COUNT, not what was compacted. When the head
 * of the history is not materialised (`windowFromIndex > 0`) the visible count
 * is a floor, not an answer, so fall back to the marker's own
 * `compactedCount` — recorded at compaction time from the full history, hence
 * unaffected by any later window.
 *
 * The two numbers measure slightly different things: bubbles (merged, tool
 * traffic hidden) versus raw history entries (a tool_use/tool_result pair is two
 * entries and no bubble of its own). So the fallback is reported as approximate
 * rather than pretending to bubble precision — an honest "≈340" beats an exact
 * and absurd "2".
 */
object CompactDividerCount {

    data class Result(
        /** Number to display. */
        val count: Int,
        /**
         * True when [count] came from the marker instead of a direct count of
         * loaded bubbles, so the UI should mark it as approximate.
         */
        val approximate: Boolean,
    )

    /**
     * @param visibleBubblesAbove non-system bubbles above the divider in the
     *   materialised list — exact only when the list starts at the beginning of
     *   the session.
     * @param windowFromIndex index of the first materialised row; 0 means the
     *   whole history is loaded.
     * @param markerCompactedCount `compactedCount` persisted on the marker.
     */
    fun resolve(
        visibleBubblesAbove: Int,
        windowFromIndex: Int,
        markerCompactedCount: Int,
    ): Result {
        val visible = visibleBubblesAbove.coerceAtLeast(0)
        if (windowFromIndex <= 0) {
            // Whole history materialised → the direct count is the truth.
            return Result(count = visible, approximate = false)
        }
        // Head not loaded. The marker knows how much went into the summary;
        // never report LESS than what is demonstrably above the divider.
        val fromMarker = markerCompactedCount.coerceAtLeast(0)
        if (fromMarker <= visible) {
            // Marker count unusable (0 on legacy rows, or somehow smaller than
            // what we can see) — the visible count is then a lower bound we can
            // at least defend, still flagged approximate because rows above the
            // window are not counted.
            return Result(count = visible, approximate = true)
        }
        return Result(count = fromMarker, approximate = true)
    }
}
