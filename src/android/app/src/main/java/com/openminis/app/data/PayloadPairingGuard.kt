package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-payload-pairing-guard] Last line of defence before a request leaves the
 * app: every `tool_result` must have its `tool_use` earlier in the SAME
 * payload, and vice versa.
 *
 * ## Why a guard and not just correct slicing
 *
 * Reported live:
 *
 * ```
 * Provider error: [400] Invalid request (TOOL_USE_RESULT_MISMATCH):
 *   unexpected `tool_use_id` found in `tool_result` blocks: toolu_bdrk_011CJ…
 *   Each `tool_result` block must have a corresponding `tool_use` block in the
 *   previous message.
 * ```
 *
 * Every producer of the payload is supposed to keep pairing intact — the
 * compaction slicer, the degraded-tail path, the offload rewriter, message
 * surgery. That is four independent places, and any of them getting it wrong
 * turns a working session into a hard 400 on every send: unrecoverable from
 * the user's side, because the broken pair is in the persisted history.
 *
 * A single check at the choke point costs one pass over the payload and makes
 * that class of bug impossible to ship. It is not a substitute for correct
 * slicing (the callers still log when they had to drop something) — it is the
 * difference between "a slicing bug degrades this turn" and "a slicing bug
 * bricks the session".
 *
 * ## What it does NOT do
 *
 * It does not invent placeholder results for calls whose result is missing —
 * the agent loop's own sanitizer owns that case and can say what happened
 * ("interrupted"). Here an unanswered `tool_use` is simply dropped, because
 * the alternative (sending it) is the same 400 in the other direction.
 */
object PayloadPairingGuard {

    data class Result(
        val messages: List<LLMMessage>,
        val droppedResults: Int,
        val droppedUses: Int,
    ) {
        val mutated: Boolean get() = droppedResults > 0 || droppedUses > 0
    }

    /**
     * Drop unpaired tool parts from [messages].
     *
     * Order matters: a `tool_result` is valid only if its `tool_use` appeared
     * in an EARLIER message, so the scan is positional, not a set-membership
     * test. A result that precedes its own call is exactly as invalid as one
     * whose call is absent, and providers reject both.
     */
    fun enforce(messages: List<LLMMessage>): Result {
        // 1. tool_use ids by first position they appear at.
        val useAt = HashMap<String, Int>()
        for ((idx, msg) in messages.withIndex()) {
            for (part in msg.contentParts) {
                if (part is AgentContentPart.ToolUse) useAt.putIfAbsent(part.id, idx)
            }
        }
        // 2. tool_result ids that are actually answered at a valid position.
        val answered = HashSet<String>()
        for ((idx, msg) in messages.withIndex()) {
            for (part in msg.contentParts) {
                if (part is AgentContentPart.ToolResult) {
                    val at = useAt[part.id]
                    if (at != null && at < idx) answered.add(part.id)
                }
            }
        }

        var droppedResults = 0
        var droppedUses = 0
        val out = ArrayList<LLMMessage>(messages.size)
        for ((idx, msg) in messages.withIndex()) {
            if (msg.contentParts.isEmpty()) {
                out.add(msg)
                continue
            }
            val kept = ArrayList<AgentContentPart>(msg.contentParts.size)
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.ToolResult -> {
                        val at = useAt[part.id]
                        if (at != null && at < idx) kept.add(part) else droppedResults++
                    }
                    is AgentContentPart.ToolUse -> {
                        if (part.id in answered) kept.add(part) else droppedUses++
                    }
                    else -> kept.add(part)
                }
            }
            if (kept.size == msg.contentParts.size) {
                out.add(msg)
                continue
            }
            // A message reduced to nothing is dropped rather than sent as a
            // contentless turn — providers reject empty content blocks, and an
            // empty turn also breaks strict user/assistant alternation.
            if (kept.isEmpty() && msg.content.isBlank()) continue
            out.add(msg.copy(contentParts = kept))
        }
        return Result(out, droppedResults, droppedUses)
    }
}
