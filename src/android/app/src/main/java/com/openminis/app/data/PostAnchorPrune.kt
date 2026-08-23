package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-postanchor-payload-bloat] Trim oversize tool_results from a compacted
 * history slice while keeping the freshest turns verbatim.
 *
 * ## The failure this fixes
 *
 * Observed live across parallel sessions: identical model, identical prompt,
 * but the request body ranged from ~243 KB (accepted) to ~1.28 MB (rejected
 * with a misleading `sensitive_words_detected` code). The bytes were the
 * cause, not the content.
 *
 * `effectiveAgentHistory` (v2 markers) already prunes oversize tool_results in
 * the **preAnchor** slice — the lookback window before the compact anchor —
 * because the summary already covers them. But the **postAnchor** slice
 * (everything after the anchor) was sent RAW. In a tool-heavy session that ran
 * many turns since the last compact, postAnchor accumulates dozens of large
 * `file_read` / `grep` / `shell_execute` tool_results (no images — measured:
 * plain text output) and inflates the body past 1 MB.
 *
 * Cloudflare-fronted relays with a 1M context window swallow that; stricter
 * relays (direct-origin, 200K window) reject the whole request. Distributing
 * "gorouter is more stable than the others" was a real observation — but the
 * right fix is to stop emitting the bloat, not to depend on the lenient host.
 *
 * ## What is protected
 *
 * The most recent [protectRecentUserTextTurns] user-text turns — and every
 * message after the first of them — are kept byte-for-byte. That is the live
 * working context the model still needs. Only tool_results in older turns
 * (already summarised) are eligible for removal. When a tool_result is
 * dropped, its paired tool_use (same id) is stripped too, so the model never
 * sees a dangling tool_use without its result.
 */
object PostAnchorPrune {

    const val DEFAULT_MAX_TOOL_RESULT_CHARS = 1000

    data class Result(
        val messages: List<LLMMessage>,
        val droppedToolResultCount: Int,
    )

    /**
     * @param messages the postAnchor slice (chronological order).
     * @param protectRecentUserTextTurns number of trailing user-text turns to
     *   keep verbatim. Everything from the first of them onward is protected.
     *   `<= 0` means nothing is protected (whole slice prunable).
     * @param maxToolResultChars tool_results longer than this (in the prunable
     *   head) are dropped.
     */
    fun prune(
        messages: List<LLMMessage>,
        protectRecentUserTextTurns: Int,
        maxToolResultChars: Int = DEFAULT_MAX_TOOL_RESULT_CHARS,
    ): Result {
        if (messages.isEmpty()) return Result(messages, 0)

        // Find the first message that must be kept verbatim: walk back from the
        // end collecting user-text turns until we have `protectRecentUserTextTurns`
        // of them. Everything from that index onward is protected; everything
        // before it is prunable.
        var protectedFromIdx = messages.size // default: nothing protected
        if (protectRecentUserTextTurns > 0) {
            var seenUserTextTurns = 0
            var i = messages.size - 1
            while (i >= 0) {
                if (isUserTextTurn(messages[i])) {
                    seenUserTextTurns += 1
                    protectedFromIdx = i
                    if (seenUserTextTurns >= protectRecentUserTextTurns) break
                }
                i -= 1
            }
        }

        // Nothing before the protected tail — nothing to prune.
        if (protectedFromIdx <= 0) return Result(messages, 0)

        val prunableHead = messages.subList(0, protectedFromIdx)

        // Pass 1: collect ids of oversize tool_results in the prunable head.
        val droppedToolIds = HashSet<String>()
        var droppedCount = 0
        for (msg in prunableHead) {
            for (part in msg.contentParts) {
                if (part is AgentContentPart.ToolResult && part.content.length > maxToolResultChars) {
                    droppedToolIds.add(part.id)
                    droppedCount += 1
                }
            }
        }

        if (droppedCount == 0) return Result(messages, 0)

        // Pass 2: rebuild the head without the dropped tool_result / tool_use
        // parts, skipping any message left with no parts. The protected tail is
        // appended unchanged.
        val rebuilt = ArrayList<LLMMessage>(messages.size)
        for (msg in prunableHead) {
            if (msg.contentParts.isEmpty()) {
                rebuilt.add(msg) // plain text-only message — nothing to prune
                continue
            }
            val kept = msg.contentParts.filter { part ->
                when (part) {
                    is AgentContentPart.ToolUse -> !droppedToolIds.contains(part.id)
                    is AgentContentPart.ToolResult -> !droppedToolIds.contains(part.id)
                    else -> true
                }
            }
            if (kept.isEmpty()) continue // skip empty shells
            rebuilt.add(msg.copy(contentParts = kept))
        }
        rebuilt.addAll(messages.subList(protectedFromIdx, messages.size))
        return Result(rebuilt, droppedCount)
    }

    private fun isUserTextTurn(msg: LLMMessage): Boolean =
        msg.role == LLMMessage.Role.USER &&
            (msg.content.isNotBlank() ||
                msg.contentParts.any { it is AgentContentPart.Text && it.text.isNotBlank() })
}
