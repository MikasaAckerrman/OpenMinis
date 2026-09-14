package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * Safe-split helpers exposed for unit testing. Live code uses them via
 * [ChatViewModel.generateCompactSummaryWithSplitting] — never call them
 * directly from production code. They live in a top-level (non-member,
 * non-internal) location because Kotlin's `internal` visibility doesn't
 * cross into the unitTest source set in this Gradle setup, so this file
 * is the practical surface that mirrors the same logic the production
 * path uses.
 *
 * [T-compact-safe-split] Splitting must NEVER cut inside a
 * tool_call/tool_result pair — every chunk must start on a real user turn
 * so the summarizer sees a complete fragment and the merged output does
 * not come out "twitchy" with orphan tool calls/results. The rules below
 * pin this down.
 */

/** Real user turn ≠ tool-result wrapper. */
fun isRealUserTurn(msg: LLMMessage): Boolean {
    if (msg.role != LLMMessage.Role.USER) return false
    val parts = msg.contentParts
    if (parts.isNotEmpty() && parts.all { it is AgentContentPart.ToolResult }) return false
    return msg.content.isNotBlank() ||
        parts.any { it is AgentContentPart.Text }
}

/** Indices of every safe split point — index 0 and every real user turn. */
fun findSafeSplitPoints(messages: List<LLMMessage>): List<Int> {
    val safe = mutableListOf<Int>()
    safe.add(0)
    for (i in 1 until messages.size) {
        if (isRealUserTurn(messages[i])) safe.add(i)
    }
    return safe
}

/**
 * N-way split at safe user-turn boundaries. If [n] exceeds the safe-points
 * budget, n is reduced. Never returns a 1-chunk fallback on a multi-message
 * input unless every message is in the middle of a tool sequence.
 */
fun splitAtSafeUserTurns(
    messages: List<LLMMessage>,
    n: Int,
): List<List<LLMMessage>> {
    if (n <= 1) return listOf(messages)
    if (messages.size < n * 2) {
        val adjustedN = (messages.size / 2).coerceAtLeast(2)
        return splitAtSafeUserTurns(messages, adjustedN)
    }
    val safePoints = findSafeSplitPoints(messages)
    if (safePoints.size < n + 1) {
        val adjustedN = (safePoints.size - 1).coerceAtLeast(2)
        return splitAtSafeUserTurns(messages, adjustedN)
    }
    val boundaryCount = n - 1
    val splits = mutableListOf<Int>()
    for (i in 1..boundaryCount) {
        val safeIdx = ((i.toDouble() / (boundaryCount + 1)) * (safePoints.size - 1)).toInt() + 1
        val actualIdx = safeIdx.coerceIn(1, safePoints.size - 1)
        splits.add(safePoints[actualIdx])
    }
    val chunks = mutableListOf<List<LLMMessage>>()
    var start = 0
    for (splitIdx in splits) {
        if (splitIdx > start && splitIdx <= messages.size) {
            chunks.add(messages.subList(start, splitIdx).toList())
            start = splitIdx
        }
    }
    if (start < messages.size) {
        chunks.add(messages.subList(start, messages.size).toList())
    }
    return chunks.filter { it.isNotEmpty() }
}
