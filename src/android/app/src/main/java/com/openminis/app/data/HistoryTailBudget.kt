package com.openminis.app.data

/**
 * [T-degraded-history-budget] Chooses how much of the tail of a history to
 * send when the compact anchor cannot be resolved.
 *
 * ## Why this exists
 *
 * When a marker's anchor id is missing from the in-memory history (rows
 * deleted, ids rewritten by an edit, a marker healed against a different
 * ordering), `effectiveAgentHistory` used to "degrade to full history": send
 * everything, summary dropped. The reasoning was that over-informing is safer
 * than a lone summary — true for a small session, and exactly backwards for a
 * large one. On an oversized session that degradation IS the failure: the whole
 * history goes out, including everything the compaction was supposed to fold
 * away, and the request dies again. That is what the user saw as "maybe it
 * tried to read the whole session at once, even the part it didn't compact".
 *
 * So the degraded path now sends the summary plus a BOUNDED tail. The
 * constraint that makes this non-trivial is tool pairing: a tail must not begin
 * in the middle of a tool round, or the request carries a `tool_result` whose
 * `tool_use` is gone and the provider rejects it outright. Hence cuts are only
 * allowed at "clean" user turns — a user message that carries no tool results.
 *
 * Pure logic (no Android, no JSON): the caller maps its messages into
 * [Candidate]s and applies the returned index.
 */
object HistoryTailBudget {

    /**
     * One history entry, reduced to what the cut decision needs.
     *
     * @param isCleanUserTurn true for a USER message that carries no tool
     *        results — the only safe place to start a tail.
     * @param tokens rough token cost of this entry.
     */
    data class Candidate(
        val isCleanUserTurn: Boolean,
        val tokens: Int,
    )

    /**
     * Index to start the tail at, or 0 when everything fits / no safe cut
     * exists above the budget.
     *
     * Walks back from the end accumulating cost, remembering the last safe cut
     * point seen. When the budget is exceeded, returns the most recent safe cut
     * that still fit. Never returns a mid-round index.
     *
     * Returning 0 (send everything) when NO safe cut fits is deliberate: a
     * history whose every entry is one giant tool round cannot be trimmed here
     * without corrupting it, and the caller has better tools for that case
     * (offload the payloads, or rescue). Silently emitting a broken tail would
     * turn a size failure into a protocol failure, which is harder to diagnose.
     */
    fun startIndex(candidates: List<Candidate>, budgetTokens: Int): Int {
        if (candidates.isEmpty() || budgetTokens <= 0) return 0
        var spent = 0
        var bestCut = -1
        for (i in candidates.indices.reversed()) {
            val c = candidates[i]
            if (spent + c.tokens > budgetTokens) break
            spent += c.tokens
            // A clean user turn at i means "the tail could start here".
            if (c.isCleanUserTurn) bestCut = i
        }
        // Everything fit — no trim needed.
        if (spent <= budgetTokens && bestCut == 0) return 0
        return if (bestCut >= 0) bestCut else 0
    }

    /**
     * Budget for the degraded tail: leave room for the summary, the system
     * prompt and one model answer. Deliberately conservative — the whole point
     * is to produce a request that lands.
     */
    fun tailBudget(contextWindow: Int, summaryTokens: Int): Int {
        val reserve = 20_000 + summaryTokens
        return (contextWindow - reserve).coerceAtLeast(4_000)
    }
}
