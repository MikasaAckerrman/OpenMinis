package com.openminis.app.data

import com.openminis.app.data.model.LLMMessage

/**
 * [T-reasoning-elision] Stop re-uploading the model's ancient scratchpads.
 *
 * ## The measured failure
 *
 * Request telemetry (30.09, glm-5.3 via dashscope): a 6000-row session sent
 * `prompt_tokens=181937` — 825 KB uploaded on EVERY turn (4.5 s upload +
 * 5.3 s server ingest before the first byte). The tool_result bloat was
 * already fought (PostAnchorPrune dropped 191 oversize results that same
 * turn), yet the body stayed huge: the bulk that survived was
 * `reasoningContent` echoed back from ~100 older assistant turns — 2-5 KB
 * of chain-of-thought per turn that the model re-reads with no benefit:
 * reasoning is a transient scratchpad, not a fact the conversation depends
 * on (the final text + tool_results carry the outcome).
 *
 * ## The contract
 *
 * - The LAST [protectRecentUserTextTurns] user-text turns — and every
 *   message from the first of them onward — keep their reasoning verbatim:
 *   that is the live working context (and the immediately preceding
 *   assistant scratchpad is genuinely useful continuity).
 * - Older assistant turns with reasoning longer than [minCharsToElide] get
 *   a short STUB, never `null`: DeepSeek V4 rejects histories where an
 *   assistant turn lacks `reasoning_content` once thinking is enabled, so
 *   the FIELD must stay present — only the bytes shrink.
 * - Message count and ordering never change; only the field's content.
 */
object ReasoningElider {

    /** Default protected tail — matches the compact path's keepN. */
    const val DEFAULT_PROTECT_RECENT_USER_TEXT_TURNS = 6

    /**
     * Reasoning shorter than this is left alone — the win is in the
     * multi-KB blocks, and micro-elisions would churn tokens for nothing.
     */
    const val DEFAULT_MIN_CHARS_TO_ELIDE = 600

    private const val STUB = "[reasoning elided]"

    data class Result(
        val messages: List<LLMMessage>,
        val elidedCount: Int,
        val charsSaved: Int,
    )

    fun elide(
        messages: List<LLMMessage>,
        protectRecentUserTextTurns: Int = DEFAULT_PROTECT_RECENT_USER_TEXT_TURNS,
        minCharsToElide: Int = DEFAULT_MIN_CHARS_TO_ELIDE,
    ): Result {
        if (messages.isEmpty()) return Result(messages, 0, 0)

        // Walk back from the end collecting user-text turns; everything from
        // the first of the last `protectRecentUserTextTurns` of them onward
        // is protected (same walk as PostAnchorPrune).
        var protectedFromIdx = messages.size
        if (protectRecentUserTextTurns > 0) {
            var seen = 0
            var i = messages.size - 1
            while (i >= 0) {
                val m = messages[i]
                if (m.role == LLMMessage.Role.USER &&
                    (m.content.isNotBlank() ||
                        m.contentParts.any { it is com.openminis.app.data.model.AgentContentPart.Text && it.text.isNotBlank() })
                ) {
                    seen += 1
                    protectedFromIdx = i
                    if (seen >= protectRecentUserTextTurns) break
                }
                i -= 1
            }
        }

        var elided = 0
        var saved = 0
        var changed = false
        val out = ArrayList<LLMMessage>(messages.size)
        for ((idx, m) in messages.withIndex()) {
            val rc = m.reasoningContent
            if (idx < protectedFromIdx &&
                m.role == LLMMessage.Role.ASSISTANT &&
                rc != null &&
                rc.length > minCharsToElide
            ) {
                out.add(m.copy(reasoningContent = STUB))
                elided += 1
                saved += rc.length - STUB.length
                changed = true
            } else {
                out.add(m)
            }
        }
        if (!changed) return Result(messages, 0, 0)
        return Result(out, elided, saved)
    }
}
