package com.openminis.app.data

/**
 * [T-compact-mechanical-fallback] Deterministic, model-free digest used when
 * route-laddered LLM compaction is unavailable (provider 400 / invalid key /
 * quota / network) or returned nothing. The user's hard requirement: a session
 * must ALWAYS be compactable — otherwise a full context window kills all work.
 *
 * Contract (deliberately different from the LLM path):
 *  - **Extractive, not generative.** User messages are kept VERBATIM (the
 *    source of intent); no paraphrase is invented. Tool outputs and assistant
 *    chains are dropped — they re-derive from intent where needed.
 *  - **Newest-first within budget.** Recent user turns matter more for
 *    continuation than the opening ones, so when the budget is exceeded the
 *    oldest ones are dropped, with a visible count.
 *  - **Last assistant message kept** — that is where "where we stopped" lives.
 *  - Pure logic: no I/O, no provider, no DateTime — fully unit-testable.
 *  - Kapped strictly: caller passes a small budget so the digest itself can
 *    never overflow a small-context model.
 *
 * Failure mode: if the compacted range has neither user nor assistant text,
 * there is nothing to preserve and [buildDigest] returns a blank string —
 * caller surfaces that as a regular error.
 */
object MechanicalCompact {

    data class Turn(val isUser: Boolean, val text: String)

    /** Per-message verbatim cap; long pastes keep their head. */
    private const val USER_MSG_CAP = 600
    /** Total digest budget FLOOR in chars — never smaller, even on tiny windows. */
    private const val TOTAL_CAP_MIN = 2_000
    /** Total digest budget CEILING in chars — big windows don't need more. */
    private const val TOTAL_CAP_MAX = 24_000
    /** The literal "where we stopped" anchor. */
    private const val LAST_ASSISTANT_CAP = 1200

    /**
     * [T-tail-token-budget] Digest budget derived from the ACTIVE model's
     * window instead of a flat 6000: a mechanical digest that overflows the
     * small-context model it is compacting FOR recreates the very 400 it
     * exists to escape. ~3 chars per token is the coarse Russian/English
     * mixed-text ratio used elsewhere in the estimator stack; the digest is
     * a small share of the window because it REPLACES the whole compacted
     * range and still has to coexist with the protected tail, system prompt
     * and output budget.
     */
    fun digestCharBudget(contextWindowTokens: Int): Int =
        (contextWindowTokens / 8 * 3).coerceIn(TOTAL_CAP_MIN, TOTAL_CAP_MAX)

    fun buildDigest(turns: List<Turn>, charBudget: Int = digestCharBudget(16_000)): String {
        val totalCap = charBudget.coerceIn(TOTAL_CAP_MIN, TOTAL_CAP_MAX)
        val userTexts = turns.filter { it.isUser }.map { it.text.trim() }.filter { it.isNotEmpty() }
        val lastAssistant = turns.lastOrNull { !it.isUser && it.text.isNotBlank() }?.text?.trim()
        if (userTexts.isEmpty() && lastAssistant == null) return ""

        val header = "[Контекст сжат механически: модель была недоступна]\n" +
            "[Ниже — дословные требования пользователя, новые первее]\n\n"

        // The ENTIRE digest (header, kept turns, skipped note, assistant
        // anchor) is paid for out of ONE budget so the result provably fits
        // the window it was budgeted for — the header and the trailing
        // blocks used to be appended on top of the cap (+~1.5k chars the
        // small-window model was not promised). Lines are pre-rendered so
        // the rollback below can account for them by exact length.
        val lines = userTexts.asReversed()
            .mapIndexed { idx, t -> "${idx + 1}. ${t.take(USER_MSG_CAP)}\n\n" }
        var budget = totalCap - header.length
        var kept = 0
        while (kept < lines.size && lines[kept].length <= budget) {
            budget -= lines[kept].length
            kept++
        }
        var skipped = lines.size - kept
        // The skipped note is a REQUIRED part of the digest (the user must
        // never lose messages silently), so it outranks the newest kept
        // line: if the remaining budget can't pay for the note, roll lines
        // back until it can. Bounded: kept decreases every iteration.
        var note = ""
        while (skipped > 0) {
            note = "…ещё $skipped более ранних сообщений пользователя опущено (бюджет).\n\n"
            if (note.length <= budget) break
            if (kept == 0) { note = ""; break } // degenerate: note alone exceeds the budget
            kept -= 1
            budget += lines[kept].length
            skipped += 1
        }
        if (lastAssistant != null && budget - note.length > 300) {
            val anchorHeader = "Последний ответ ассистента (где остановились, дословно):\n"
            val room = budget - note.length - anchorHeader.length
            if (room > 0) {
                note += anchorHeader + lastAssistant.take(minOf(LAST_ASSISTANT_CAP, room))
            }
        }
        return (header + lines.subList(0, kept).joinToString("") + note).trim()
    }
}
