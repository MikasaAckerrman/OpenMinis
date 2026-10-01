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

    /**
     * Default protected tail — the FRESH tier: reasoning kept VERBATIM.
     * Three turns of the model's own working memory is the continuity the
     * tool-loop actually reads; older scratchpads are replayed-continuity
     * at best. (Was 6 — the flat full protection; telemetry showed the
     * body still carried hundreds of KB of dead scratchpad.)
     */
    const val DEFAULT_PROTECT_RECENT_USER_TEXT_TURNS = 3

    /**
     * The WARM tier: user-text turns between the fresh tail and
     * [DEFAULT_PROTECT_RECENT_USER_TEXT_TURNS] + this many get their
     * reasoning HEAD-TRIMMED to [WARM_HEAD_CHARS] instead of stubbed —
     * the model keeps its earlier plan statement, loses the dead middle.
     */
    const val DEFAULT_WARM_TAIL_USER_TEXT_TURNS = 3

    /** Head budget for warm-tier reasoning. */
    const val WARM_HEAD_CHARS = 400

    /**
     * Reasoning shorter than this is left alone — the win is in the
     * multi-KB blocks, and micro-elisions would churn tokens for nothing.
     */
    const val DEFAULT_MIN_CHARS_TO_ELIDE = 600

    private const val STUB = "[reasoning elided]"
    private const val ELLIPSIS = "…[elided]"

    /**
     * [T-elider-memo] Per-message memo: within a turn the elider runs on
     * EVERY request build (measured 01.10: three `ReasoningElide` logs per
     * single retry — ~350ms × 3 on a 7470-row session, re-stubbing the
     * SAME 275 blocks / 650KB each time), and between tool rounds the
     * history prefix instances survive verbatim. The transform is pure
     * (message + tier → message), so the transformed instances are
     * memoized by identity. Weak keys: entries die with the history
     * itself. Synchronized map — the elider runs on IO dispatchers.
     */
    private class TierMemo(var stub: LLMMessage? = null, var warm: LLMMessage? = null)
    private val memo: MutableMap<LLMMessage, TierMemo> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<LLMMessage, TierMemo>())

    data class Result(
        val messages: List<LLMMessage>,
        val elidedCount: Int,
        val charsSaved: Int,
    )

    fun elide(
        messages: List<LLMMessage>,
        protectRecentUserTextTurns: Int = DEFAULT_PROTECT_RECENT_USER_TEXT_TURNS,
        minCharsToElide: Int = DEFAULT_MIN_CHARS_TO_ELIDE,
        warmTailUserTextTurns: Int = DEFAULT_WARM_TAIL_USER_TEXT_TURNS,
        warmHeadChars: Int = WARM_HEAD_CHARS,
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
        // Warm boundary: the same walk continued over the next
        // `warmTailUserTextTurns` user turns — turns in
        // [warmFromIdx, protectedFromIdx) get head-trimmed, not stubbed.
        var warmFromIdx = protectedFromIdx
        if (warmTailUserTextTurns > 0) {
            var seen = 0
            var i = protectedFromIdx - 1
            while (i >= 0) {
                val m = messages[i]
                if (m.role == LLMMessage.Role.USER &&
                    (m.content.isNotBlank() ||
                        m.contentParts.any { it is com.openminis.app.data.model.AgentContentPart.Text && it.text.isNotBlank() })
                ) {
                    seen += 1
                    warmFromIdx = i
                    if (seen >= warmTailUserTextTurns) break
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
            if (m.role == LLMMessage.Role.ASSISTANT && rc != null && rc.length > minCharsToElide) {
                when {
                    idx >= protectedFromIdx -> out.add(m)
                    idx >= warmFromIdx -> {
                        // Warm tier: keep the head (the plan statement),
                        // drop the dead middle. Memoized per identity+tier.
                        val rc2 = rc
                        val entry = memo.getOrPut(m) { TierMemo() }
                        val transformed = entry.warm ?: m.copy(
                            reasoningContent = rc2.take(warmHeadChars) + ELLIPSIS
                        ).also { entry.warm = it }
                        out.add(transformed)
                        elided += 1
                        saved += rc2.length - warmHeadChars - ELLIPSIS.length
                        changed = true
                    }
                    else -> {
                        val entry = memo.getOrPut(m) { TierMemo() }
                        val transformed = entry.stub ?: m.copy(
                            reasoningContent = STUB
                        ).also { entry.stub = it }
                        out.add(transformed)
                        elided += 1
                        saved += rc.length - STUB.length
                        changed = true
                    }
                }
            } else {
                out.add(m)
            }
        }
        if (!changed) return Result(messages, 0, 0)
        return Result(out, elided, saved)
    }
}
