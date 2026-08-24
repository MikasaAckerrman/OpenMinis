package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-request-byte-budget] Final size gate at the provider boundary: keep the
 * serialized request body under a byte ceiling by eliding OLD, large tool
 * results — while the freshest working context is always sent verbatim.
 *
 * ## The failure this closes
 *
 * Measured live across parallel sessions on the SAME model + prompt: request
 * bodies ranged 243 KB (accepted everywhere) to 1.28 MB (rejected by a
 * 200K-window relay with a misleading `sensitive_words_detected` code). The
 * bytes were the cause, not the content. `ImageBudget` already caps *image*
 * bytes at this boundary (`MAX_REQUEST_BYTES`); nothing capped *text* — a
 * tool-heavy session accumulates dozens of large `file_read` / `grep` /
 * `shell_execute` results and blows the body past 1 MB.
 *
 * Offload (token-gated, once per turn) and `PostAnchorPrune` (compact path)
 * already shrink history, but neither measures the actual request body in
 * bytes. This is the belt-and-braces net at the exact place the bytes leave
 * the device — the analogue of `ImageBudget.planRequestBudget`.
 *
 * ## What is "нужное" (always sent verbatim)
 *
 * The design turns on the definition of the protected set. Nothing in it is
 * ever elided, regardless of ceiling:
 *   - the system prompt (passed separately; not this module's concern),
 *   - the current user message and everything after the protected boundary,
 *   - the most recent [protectRecentUserTextTurns] user-text turns,
 *   - any tool_result that is small or is already an offload stub.
 *
 * ## What is "мусор" (elided oldest-and-largest first)
 *
 * Tool_result CONTENT in turns older than the protected tail, longest first,
 * until the body fits. The tool_result PART is kept (id preserved) with its
 * content replaced by a short placeholder, so tool_use/tool_result pairing
 * never breaks — safer than dropping the message. The full output still lives
 * in `agentHistory` (audit trail) and, when offloaded, on disk at the path the
 * stub carries, so the model can `file_read` it back.
 *
 * Pure logic (no Android, no JSON): byte cost is UTF-8 length, adequate for a
 * "does the body fit" decision. The caller applies the returned messages.
 */
object RequestBudget {

    /**
     * Conservative body ceiling. The live log measured 250 KB accepted on
     * every relay and 1.28 MB rejected.
     *
     * [T-postanchor-preserve-live-context] Raised 300 KB → 1 MB. The original
     * 300 KB was a 4x safety margin below the single observed failure, and it
     * cost real context: on a long tool-heavy session it elided results
     * measured as small as 3.8 KB, so the model lost most of the live thread
     * and fell back on the compact summary — which describes the START of the
     * conversation. That produced the "it answers as if from the beginning of
     * the session" symptom. 1 MB still sits below the only rejection ever
     * measured (1.28 MB) while leaving 3.3x more working room.
     */
    const val DEFAULT_MAX_BODY_BYTES = 1_000_000

    /**
     * Tool results at or below this many chars are never worth eliding.
     *
     * [T-postanchor-preserve-live-context] Raised 1000 → 8000. At 1000 the gate
     * treated ordinary command output as elidable, so hitting the ceiling
     * gutted dozens of small-but-relevant results instead of the few genuinely
     * huge ones it was written for.
     */
    const val MIN_ELIDABLE_TOOL_RESULT_CHARS = 8000

    /** Marker on an elided tool_result so the model (and diagnostics) can see why. */
    const val ELIDED_PREFIX = "[tool result elided to fit request budget"

    data class Report(
        val messages: List<LLMMessage>,
        val elidedToolResultCount: Int,
        val bytesBefore: Int,
        val bytesAfter: Int,
    )

    /**
     * @param messages the full message list about to be serialized (system
     *   prompt excluded — it is a separate field and never elided).
     * @param protectRecentUserTextTurns trailing user-text turns kept verbatim.
     * @param maxBodyBytes ceiling on the estimated serialized body.
     */
    fun plan(
        messages: List<LLMMessage>,
        protectRecentUserTextTurns: Int,
        maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
    ): Report {
        val bytesBefore = estimateBytes(messages)
        if (messages.isEmpty() || bytesBefore <= maxBodyBytes) {
            return Report(messages, 0, bytesBefore, bytesBefore)
        }

        val protectedFromIdx = protectedBoundary(messages, protectRecentUserTextTurns)

        // Collect elision candidates: (messageIdx, partIdx, contentLength),
        // only in the prunable head, skipping anything already reduced (offload
        // stub or an earlier elision).
        //
        // [T-postanchor-preserve-live-context] THE CHAR THRESHOLD IS A
        // PREFERENCE, NOT A FILTER. It used to be a hard `>` filter, which left
        // the same hole the postAnchor valve had: a body made of MANY MID-SIZED
        // results (e.g. 200 x 5 KB ≈ 1 MB) produced an EMPTY candidate list, so
        // nothing was elided and the oversize request went out anyway — exactly
        // the failure this gate exists to prevent. Now everything is a
        // candidate, ordered so that results above the threshold go first;
        // smaller ones are touched only while the body still does not fit.
        data class Candidate(val msgIdx: Int, val partIdx: Int, val len: Int)
        val big = ArrayList<Candidate>()
        val small = ArrayList<Candidate>()
        for (mi in 0 until protectedFromIdx) {
            val parts = messages[mi].contentParts
            for (pi in parts.indices) {
                val p = parts[pi]
                if (p is AgentContentPart.ToolResult && !isAlreadyReduced(p.content)) {
                    val c = Candidate(mi, pi, p.content.length)
                    if (p.content.length > MIN_ELIDABLE_TOOL_RESULT_CHARS) big.add(c)
                    else small.add(c)
                }
            }
        }
        if (big.isEmpty() && small.isEmpty()) {
            return Report(messages, 0, bytesBefore, bytesBefore)
        }

        // Largest first within each tier — reclaim the most bytes per elision so
        // we touch as few results as possible to get under the ceiling.
        big.sortByDescending { it.len }
        small.sortByDescending { it.len }
        val candidates = big + small

        // Work on a mutable copy of only the parts lists we change.
        val editedParts = HashMap<Int, MutableList<AgentContentPart>>()
        var running = bytesBefore
        var elided = 0
        for (c in candidates) {
            if (running <= maxBodyBytes) break
            val partsList = editedParts.getOrPut(c.msgIdx) {
                messages[c.msgIdx].contentParts.toMutableList()
            }
            val original = partsList[c.partIdx] as AgentContentPart.ToolResult
            val placeholder = elisionPlaceholder(original)
            partsList[c.partIdx] = original.copy(content = placeholder)
            // bytes reclaimed ≈ original content bytes − placeholder bytes
            running -= (utf8(original.content) - utf8(placeholder))
            elided += 1
        }

        if (elided == 0) return Report(messages, 0, bytesBefore, bytesBefore)

        val out = ArrayList<LLMMessage>(messages.size)
        for (i in messages.indices) {
            val edited = editedParts[i]
            if (edited == null) out.add(messages[i])
            else out.add(messages[i].copy(contentParts = edited))
        }
        return Report(out, elided, bytesBefore, estimateBytes(out))
    }

    /**
     * First index that must be kept verbatim: walk back from the end
     * collecting user-text turns until [protectRecentUserTextTurns] are seen.
     * Everything from that index on is protected. `<=0` protects nothing.
     */
    private fun protectedBoundary(messages: List<LLMMessage>, protectRecentUserTextTurns: Int): Int {
        if (protectRecentUserTextTurns <= 0) return 0
        var seen = 0
        var boundary = messages.size
        var i = messages.size - 1
        while (i >= 0) {
            if (isUserTextTurn(messages[i])) {
                seen += 1
                boundary = i
                if (seen >= protectRecentUserTextTurns) break
            }
            i -= 1
        }
        // If fewer than N user-text turns exist, boundary is the earliest one
        // seen (or messages.size when none) — protecting everything from there.
        return boundary
    }

    private fun isUserTextTurn(msg: LLMMessage): Boolean =
        msg.role == LLMMessage.Role.USER &&
            (msg.content.isNotBlank() ||
                msg.contentParts.any { it is AgentContentPart.Text && it.text.isNotBlank() })

    private fun isAlreadyReduced(content: String): Boolean =
        content.startsWith(ContextOffload.OFFLOADED_PREFIX) || content.startsWith(ELIDED_PREFIX)

    private fun elisionPlaceholder(part: AgentContentPart.ToolResult): String {
        val n = part.content.length
        val where = part.imageLinuxPath?.let { "; original at $it (re-fetch with read_image)" }
            ?: "; full output remains in session history"
        return "$ELIDED_PREFIX — ${part.name}, $n chars$where]"
    }

    /** Estimated serialized body size: UTF-8 bytes of every text-bearing field. */
    fun estimateBytes(messages: List<LLMMessage>): Int {
        var total = 0
        for (msg in messages) {
            total += utf8(msg.content)
            total += msg.reasoningContent?.let { utf8(it) } ?: 0
            for (part in msg.contentParts) {
                total += when (part) {
                    is AgentContentPart.Text -> utf8(part.text)
                    is AgentContentPart.ToolUse -> utf8(part.input.toString())
                    is AgentContentPart.ToolResult -> utf8(part.content)
                    is AgentContentPart.ImageData -> 0 // ImageBudget owns image bytes
                }
            }
        }
        return total
    }

    private fun utf8(s: String): Int = s.toByteArray(Charsets.UTF_8).size
}
