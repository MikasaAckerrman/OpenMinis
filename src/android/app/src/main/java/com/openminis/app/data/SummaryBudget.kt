package com.openminis.app.data

/**
 * [T-summary-budget] Size discipline for the compact summary — the payload
 * that rides in EVERY request after a compaction, forever.
 *
 * ## Why this exists
 *
 * The tail is token-budgeted ([ProtectedTail.tailBudgetTokens]) and the
 * degraded path reserves room for the summary ([HistoryTailBudget]), but the
 * summary ITSELF was never sized against the reader's window:
 *
 *  - WRITE side: the summariser's maxOut was `min(8192, compactModelWindow -
 *    input)` — sized by whatever model ran the summary (possibly a large
 *    fallback from the route ladder), not by the model that reads it.
 *  - READ side: [effectiveAgentHistory] inlined the stored summary verbatim.
 *    A session compacted on a 128k model then switched to an 8k model sent a
 *    summary that ALONE could exceed the entire window — with the tail,
 *    system prompt and tools on top. Nothing guarded it.
 *
 * Supermemory's operating philosophy (the memory engine this budget mirrors):
 * the persistent context layer must be a TINY fraction of the window — their
 * profiles are a few lines for a ~99% context reduction. A summary is a
 * distillate, not a transcript: at window/8 it stays a rounding error on a
 * big model (16k of 128k) and physically fits a small one (1k of 8k).
 *
 * The full summary always survives untouched in the session DB (audit
 * trail); the clamp only bounds what goes on the wire.
 *
 * Pure logic — unit-tested.
 */
object SummaryBudget {

    /** Summary share of the reader's window: 1/8, floored/capped. */
    fun maxTokens(contextWindow: Int): Int = (contextWindow / 8).coerceIn(512, 8_192)

    /**
     * Char budget for [clamp]. ~4 chars/token is deliberately conservative
     * (RU text averages ~3, JSON/code more): better to send a slightly
     * shorter summary than to trust an optimistic ratio on the wire.
     */
    fun maxChars(contextWindow: Int): Int = maxTokens(contextWindow) * 4

    /**
     * Bound [summary] to the reader's window. Unchanged when it already
     * fits. When it does not: keep the head (cut at the last full line),
     * re-append the critical identifiers (paths / URLs / hashes — the class
     * of fact a resuming agent cannot re-derive) found in the DROPPED part,
     * and mark the cut visibly. Mirrors the summarizer's own
     * [CompactQuality]/summarizeBody head+refs contract so a clamped summary
     * degrades exactly like a summarizer-truncated body.
     */
    fun clamp(summary: String, contextWindow: Int): String {
        val maxChars = maxChars(contextWindow)
        if (summary.length <= maxChars) return summary
        val headEnd = summary.lastIndexOf('\n', maxChars).let { nl ->
            if (nl >= maxChars / 2) nl else maxChars
        }
        val head = summary.substring(0, headEnd).trimEnd()
        val dropped = summary.substring(headEnd)
        val refs = CompactQuality.criticalFacts(dropped, limit = 8)
            .filterNot { head.contains(it) }
        return buildString {
            append(head)
            append("\n\n[Выжимка обрезана под окно в ~")
            append(maxTokens(contextWindow))
            append(" токенов; полная версия сохранена в истории сессии.]")
            if (refs.isNotEmpty()) {
                append("\nРеференсы из опущенной части: ")
                append(refs.joinToString(" "))
            }
        }
    }
}
