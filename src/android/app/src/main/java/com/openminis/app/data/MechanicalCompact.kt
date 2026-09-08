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
    /** Total digest budget in chars — fits even a 8K-token window. */
    private const val TOTAL_CAP = 6000
    /** The literal "where we stopped" anchor. */
    private const val LAST_ASSISTANT_CAP = 1200

    fun buildDigest(turns: List<Turn>): String {
        val userTexts = turns.filter { it.isUser }.map { it.text.trim() }.filter { it.isNotEmpty() }
        val lastAssistant = turns.lastOrNull { !it.isUser && it.text.isNotBlank() }?.text?.trim()
        if (userTexts.isEmpty() && lastAssistant == null) return ""

        val sb = StringBuilder()
        sb.append("[Контекст сжат механически: модель была недоступна]\n")
        sb.append("[Ниже — дословные требования пользователя, новые первее]\n\n")

        var budget = TOTAL_CAP
        var kept = 0
        if (userTexts.isNotEmpty()) {
            val it = userTexts.asReversed().iterator()
            while (it.hasNext()) {
                val t = it.next().take(USER_MSG_CAP)
                val line = "${kept + 1}. $t\n\n"
                if (line.length > budget) break
                sb.append(line)
                budget -= line.length
                kept++
            }
            val skipped = userTexts.size - kept
            if (skipped > 0) {
                sb.append("…ещё $skipped более ранних сообщений пользователя опущено (бюджет).\n\n")
            }
        }
        if (lastAssistant != null && budget > 300) {
            sb.append("Последний ответ ассистента (где остановились, дословно):\n")
            sb.append(lastAssistant.take(LAST_ASSISTANT_CAP))
        }
        return sb.toString().trim()
    }
}
