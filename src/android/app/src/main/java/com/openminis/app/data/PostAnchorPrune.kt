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
 *
 * ## [T-postanchor-preserve-live-context] EMERGENCY VALVE, NOT A ROUTINE PASS
 *
 * The first version pruned on EVERY turn, and that was the wrong contract for
 * this slice. Read the asymmetry carefully:
 *
 *  - **preAnchor** (before the compact anchor) is covered by the summary.
 *    Dropping a tool_result there loses nothing the model cannot recover from
 *    the summary — the compaction engine already distilled it.
 *  - **postAnchor** (after the anchor) is covered by NOTHING. It is the live
 *    thread the user is having right now. Dropping content there is deletion,
 *    not compression — the exact opposite of what compaction promises
 *    ("QUALITATIVE COMPRESSION, not deletion" — compactSummarySystemPrompt).
 *
 * Pruning postAnchor unconditionally therefore gutted the live conversation:
 * the model saw a summary of the session's START plus the last few turns, with
 * the middle hollowed out, so it answered as if the old topic were still live.
 * That is a correctness bug, not a size trade-off.
 *
 * So this pass now runs only when the slice is genuinely oversize
 * ([EMERGENCY_THRESHOLD_BYTES]); a normal conversation passes through
 * untouched. When it does fire, the byte gate at the provider boundary
 * ([RequestBudget]) is the second line of defence, so the protocol-level
 * failure this module was written for still cannot come back.
 */
object PostAnchorPrune {

    /**
     * [T-postanchor-preserve-live-context] Raised 1000 → 8000. At 1000 the pass
     * removed ordinary command output; only genuinely huge results are worth
     * removing from the live slice.
     */
    const val DEFAULT_MAX_TOOL_RESULT_CHARS = 8000

    /**
     * [T-postanchor-preserve-live-context] The slice must exceed this many
     * bytes before ANY pruning happens. Below it the live thread is sent
     * verbatim — which is the normal case.
     *
     * 700 KB sits above a healthy long session and below the 1 MB provider
     * ceiling ([RequestBudget.DEFAULT_MAX_BODY_BYTES]), so this valve opens
     * before the byte gate has to elide anything, and both stay under the only
     * rejection ever measured (1.28 MB).
     */
    const val EMERGENCY_THRESHOLD_BYTES = 700_000

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
        emergencyThresholdBytes: Int = EMERGENCY_THRESHOLD_BYTES,
    ): Result {
        if (messages.isEmpty()) return Result(messages, 0)

        // [T-postanchor-preserve-live-context] EMERGENCY VALVE. postAnchor is
        // the live thread and no summary covers it, so a normal-sized slice is
        // sent verbatim. Only a genuinely bloated slice gets pruned — see the
        // class doc for why unconditional pruning here was a correctness bug.
        if (RequestBudget.estimateBytes(messages) <= emergencyThresholdBytes) {
            return Result(messages, 0)
        }

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

        // Pass 1: choose what to drop.
        //
        // [T-postanchor-preserve-live-context] SIZE-DRIVEN, NOT A FLAT CUTOFF.
        // The first version dropped every tool_result over the char threshold
        // and stopped there. Raising that threshold to spare ordinary command
        // output then opened a real hole, caught by the harness before ship: a
        // slice made of MANY MID-SIZED results (e.g. 200 x 5 KB ≈ 1 MB) sailed
        // past both this pass and the provider byte gate, because no single
        // result exceeded the threshold — so the oversize body that this module
        // exists to prevent came straight back.
        //
        // So the threshold is a PREFERENCE, not an absolute: drop the largest
        // results first and stop the moment the slice fits. Results under
        // [maxToolResultChars] are touched only if the slice still does not fit
        // after every larger one is gone — never "just because".
        val targetBytes = emergencyThresholdBytes.coerceAtLeast(0)
        data class Candidate(val id: String, val len: Int)
        val candidates = ArrayList<Candidate>()
        for (msg in prunableHead) {
            for (part in msg.contentParts) {
                if (part is AgentContentPart.ToolResult) {
                    candidates.add(Candidate(part.id, part.content.length))
                }
            }
        }
        // Preferred set first (over the char threshold), then the rest — so the
        // ordering encodes "spare ordinary output unless we must".
        val preferred = candidates.filter { it.len > maxToolResultChars }
            .sortedByDescending { it.len }
        val rest = candidates.filter { it.len <= maxToolResultChars }
            .sortedByDescending { it.len }
        val droppedToolIds = HashSet<String>()
        var droppedCount = 0
        var running = RequestBudget.estimateBytes(messages)
        for (c in preferred + rest) {
            if (running <= targetBytes) break
            // Below the preferred threshold we only continue while the slice
            // still does not fit — the loop guard above already guarantees that.
            if (droppedToolIds.add(c.id)) {
                droppedCount += 1
                running -= c.len
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
