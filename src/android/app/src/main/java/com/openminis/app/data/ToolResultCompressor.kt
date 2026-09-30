package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-tool-history-compression] Always-on head-trim for OLD tool results.
 *
 * PostAnchorPrune is an EMERGENCY valve: it only fires when a slice
 * exceeds [PostAnchorPrune.EMERGENCY_THRESHOLD_BYTES], and then it DROPS
 * whole tool_result/tool_use pairs. Between compactions a request can
 * still carry dozens of 1-8KB tool outputs from earlier turns in full —
 * every send re-uploads them and the provider re-ingests them.
 *
 * The model rarely needs the full body of an OLD tool result: it needs
 * WHAT ran (the paired tool_use is untouched) and the first lines of the
 * output (the head of a shell command, the first rows of a grep — where
 * the signal lives). So this pass trims old tool_result bodies to a head
 * budget, ALWAYS (not only under emergency), mirroring the three-tier
 * philosophy of [ReasoningElider]: recent turns verbatim, old turns
 * compacted, never dropped.
 *
 * Purity: no clock, no I/O; the caller owns ordering (runs inside
 * effectiveAgentHistory on Dispatchers.IO).
 */
object ToolResultCompressor {

    /**
     * Protected tail — user-text turns whose tool results stay VERBATIM.
     * Matches the elider's fresh tier: the current tool loop reads these.
     */
    const val DEFAULT_PROTECT_RECENT_USER_TEXT_TURNS = 3

    /** Head budget for an old tool result. */
    const val DEFAULT_HEAD_CHARS = 600

    /** Bodies shorter than this are left alone — micro-trims churn tokens. */
    const val DEFAULT_MIN_CHARS_TO_COMPRESS = 1500

    data class Result(
        val messages: List<LLMMessage>,
        val compressedCount: Int,
        val charsSaved: Int,
    )

    fun compress(
        messages: List<LLMMessage>,
        protectRecentUserTextTurns: Int = DEFAULT_PROTECT_RECENT_USER_TEXT_TURNS,
        headChars: Int = DEFAULT_HEAD_CHARS,
        minCharsToCompress: Int = DEFAULT_MIN_CHARS_TO_COMPRESS,
    ): Result {
        if (messages.isEmpty()) return Result(messages, 0, 0)

        // Same protected-tail walk as PostAnchorPrune / ReasoningElider.
        var protectedFromIdx = messages.size
        if (protectRecentUserTextTurns > 0) {
            var seen = 0
            var i = messages.size - 1
            while (i >= 0) {
                val m = messages[i]
                if (m.role == LLMMessage.Role.USER &&
                    (m.content.isNotBlank() ||
                        m.contentParts.any { it is AgentContentPart.Text && it.text.isNotBlank() })
                ) {
                    seen += 1
                    protectedFromIdx = i
                    if (seen >= protectRecentUserTextTurns) break
                }
                i -= 1
            }
        }
        if (protectedFromIdx <= 0) return Result(messages, 0, 0)

        var compressed = 0
        var saved = 0
        var changed = false
        val out = ArrayList<LLMMessage>(messages.size)
        for ((idx, m) in messages.withIndex()) {
            if (idx >= protectedFromIdx || m.contentParts.isEmpty()) {
                out.add(m)
                continue
            }
            var msgChanged = false
            val parts = m.contentParts.map { part ->
                if (part is AgentContentPart.ToolResult && part.content.length > minCharsToCompress) {
                    val head = part.content.take(headChars)
                    val tail = "\n…[+${part.content.length - headChars} chars compressed]"
                    msgChanged = true
                    compressed += 1
                    saved += part.content.length - head.length - tail.length
                    part.copy(content = head + tail)
                } else {
                    part
                }
            }
            if (msgChanged) {
                changed = true
                out.add(m.copy(contentParts = parts))
            } else {
                out.add(m)
            }
        }
        if (!changed) return Result(messages, 0, 0)
        return Result(out, compressed, saved)
    }
}
