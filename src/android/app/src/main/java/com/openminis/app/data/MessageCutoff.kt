package com.openminis.app.data

/**
 * [T-window-safe-cutoff] Where does a retry / edit / rerun cut the persisted
 * message history?
 *
 * ## The bug this replaces
 *
 * Retry and edit used to locate the DB row by ORDINAL: count how many visible
 * user bubbles precede the tapped one in `_messages.value`, then walk the full
 * `messages` table and take the N-th visible user row. That is only correct
 * while the UI list and the table are the same list.
 *
 * They are not. Since [com.openminis.app.ui.chat.ChatHistoryWindow] a long
 * session opens with a WINDOW over the newest rows (120) while the table still
 * holds every row. So on a 700-row session the tapped bubble might be the 2nd
 * visible user bubble *in the window* — ordinal 1 — and the cutoff resolved to
 * the 2nd visible user row *of the whole session*, i.e. a message from days
 * earlier. Everything after it was then deleted. One tap, the session gone
 * except its first turn.
 *
 * That is not a hypothetical: session 2c7ae861 was left with exactly one row
 * from its first day plus the rows created after the incident, while 66 compact
 * markers still pointed at anchors that no longer existed.
 *
 * ## The fix
 *
 * A bubble already knows which rows it was built from
 * ([com.openminis.app.ui.chat.ChatMessage.sourceDbIds], plus its own id for
 * bubbles created live). Resolve the cutoff from those ids directly. Identity
 * does not depend on how much of the session happens to be materialised, so the
 * window can be any size — or absent — and the cut lands on the same row.
 *
 * When no id resolves, this returns `null` and the caller MUST refuse the
 * operation. Guessing is what destroyed the history; "cannot anchor" is a
 * recoverable annoyance, a wrong anchor is not.
 *
 * Kept free of Android/Room types so every case below is unit-testable.
 */
object MessageCutoff {

    /** One persisted row, reduced to what a cutoff decision needs. */
    data class Row(val id: String, val sortOrder: Int)

    /**
     * Candidate ids for a bubble, most specific first: the rows it was built
     * from, then its own id (live bubbles are created with the persisted row id
     * as their identity before `sourceDbIds` is populated).
     */
    fun candidateIds(sourceDbIds: List<String>, bubbleId: String): List<String> {
        val out = LinkedHashSet<String>()
        sourceDbIds.forEach { if (it.isNotEmpty()) out.add(it) }
        if (bubbleId.isNotEmpty()) out.add(bubbleId)
        return out.toList()
    }

    /**
     * `sort_order` values of the rows a bubble maps to, ascending.
     * Empty when the bubble has no counterpart on disk.
     */
    fun sortOrdersFor(ids: List<String>, rows: List<Row>): List<Int> {
        if (ids.isEmpty() || rows.isEmpty()) return emptyList()
        val wanted = ids.toHashSet()
        return rows.filter { it.id in wanted }.map { it.sortOrder }.sorted()
    }

    /**
     * Cutoff for RETRY semantics: keep the tapped turn, drop everything after
     * it. `ChatDao.deleteMessagesAfter` is `sort_order >= keepCount`, so the
     * value is "one past the last row of this bubble".
     *
     * A merged assistant bubble spans several rows — the LAST one decides,
     * otherwise a retry would delete rows belonging to the very bubble the user
     * asked to keep.
     *
     * @return keepCount, or null when the bubble resolves to no row.
     */
    fun retryKeepCount(ids: List<String>, rows: List<Row>): Int? =
        sortOrdersFor(ids, rows).maxOrNull()?.plus(1)

    /**
     * Cutoff for EDIT semantics: the tapped turn is being rewritten, so it goes
     * too — keep everything strictly before it. The FIRST row of the bubble
     * decides.
     *
     * @return keepCount, or null when the bubble resolves to no row.
     */
    fun editKeepCount(ids: List<String>, rows: List<Row>): Int? =
        sortOrdersFor(ids, rows).minOrNull()

    /**
     * Sanity bound shared by both callers: a cutoff that would delete more than
     * [maxDeletable] rows on a session of [totalRows] is refused as implausible
     * for a single retry/edit gesture.
     *
     * This is belt-and-braces on top of id resolution, not the primary defence.
     * It exists because the failure mode is unbounded and silent: the old
     * ordinal path produced keepCount=1 on a 700-row session and no layer
     * questioned it. A tap on a bubble the user can SEE can only ever discard
     * the tail that is visible around it, so a cut that discards most of the
     * session means the anchor is wrong no matter how it was computed.
     */
    fun isPlausible(keepCount: Int, totalRows: Int, maxDeletable: Int = MAX_DELETABLE): Boolean {
        if (keepCount < 0 || totalRows < 0) return false
        if (keepCount > totalRows) return false
        return (totalRows - keepCount) <= maxDeletable
    }

    /**
     * Rows a single retry/edit may discard. A real gesture drops the current
     * turn's tail: an assistant answer plus its tool traffic. Agent loops make
     * that larger than you would guess (a long tool-heavy turn is easily
     * hundreds of rows), so the bound is deliberately loose — it must catch
     * "the anchor is off by days", not police normal use.
     */
    const val MAX_DELETABLE: Int = 400
}
