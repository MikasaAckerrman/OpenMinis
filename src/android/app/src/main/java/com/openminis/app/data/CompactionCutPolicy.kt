package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-compact-incremental] Cut-boundary policy for an incremental compaction
 * pass: which history positions may END a bounded slice.
 *
 * ## The problem this fixes
 *
 * The pass bounds itself to ~25k tokens of backlog, but a slice must end at a
 * SAFE position — one where a later tool_use / tool_result pair can never be
 * cut in half (that would corrupt the payload for the summarizer and trip the
 * PairingGuard on the next turn).
 *
 * The primary boundary is a plain user text message: it sits between turns,
 * so nothing is dangling. The SECONDARY boundary (the fix for the "retry
 * exploded into 40+ windows" bug) is a user message consisting ONLY of tool
 * results, provided the NEXT message does not continue the same result run:
 * a completed result run closes every tool_use of the preceding assistant
 * turn, so cutting after it orphans nothing.
 *
 * Long agent marathons between user replies used to have NO primary boundary
 * inside the budget, so the pass degraded to the full span — one retry then
 * produced a 40-window burst against a quota-limited relay. With the
 * secondary boundary the pass can always cut near the budget.
 */
object CompactionCutPolicy {

    /**
     * @param history full effective history (indexing matches [index]).
     * @param index position being tested as the LAST element of the slice.
     */
    fun isSafeBoundary(history: List<LLMMessage>, index: Int): Boolean {
        val m = history.getOrNull(index) ?: return false
        if (m.role != LLMMessage.Role.USER) return false
        val parts = m.contentParts
        if (parts.isEmpty()) return true
        val hasToolParts = parts.any {
            it is AgentContentPart.ToolResult || it is AgentContentPart.ToolUse
        }
        if (!hasToolParts) return true // plain user text — primary boundary
        if (parts.any { it is AgentContentPart.ToolUse }) return false // never cut after a call
        // Result-only message: safe unless the next message continues the run
        // (a multi-result batch serializes as several user/tool_result rows).
        val next = history.getOrNull(index + 1) ?: return true
        return !(next.role == LLMMessage.Role.USER &&
            next.contentParts.any { it is AgentContentPart.ToolResult })
    }
}
