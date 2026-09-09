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
     * the session" symptom.
     *
     * [T-image-bytes-visible] Lowered 1 MB → 900 KB. A 2026-09-09 live log
     * showed a 1.31 MB body accepted by one relay (AgentRouter) while the
     * user's other relay answered «запрос отклонен шлюзом» — nginx-fronted
     * gateways default to client_max_body_size 1m, and the JSON envelope
     * (role fields, escaping) adds ~5-10% on top of the raw part bytes. 900 KB
     * of part bytes keeps the serialized body under the strictest observed
     * limit.
     */
    const val DEFAULT_MAX_BODY_BYTES = 900_000

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
        val elidedImageCount: Int,
        val bytesBefore: Int,
        val bytesAfter: Int,
    )

    /**
     * @param messages the full message list about to be serialized (system
     *   prompt excluded — it is a separate field and never elided).
     * @param protectRecentUserTextTurns trailing user-text turns whose
     *   tool_results are never elided (the live working context).
     * @param maxBodyBytes ceiling on the estimated serialized body.
     * @param imageProtectRecentUserTextTurns trailing user-text turns whose
     *   IMAGES are never elided (smaller than the tool shield — old
     *   screenshots are compactible dead weight; see
     *   [DEFAULT_IMAGE_PROTECT_TURNS]).
     */
    fun plan(
        messages: List<LLMMessage>,
        protectRecentUserTextTurns: Int,
        maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
        imageProtectRecentUserTextTurns: Int = DEFAULT_IMAGE_PROTECT_TURNS,
    ): Report {
        val bytesBefore = estimateBytes(messages)
        if (messages.isEmpty() || bytesBefore <= maxBodyBytes) {
            return Report(messages, 0, 0, bytesBefore, bytesBefore)
        }

        val protectedFromIdx = protectedBoundary(messages, protectRecentUserTextTurns)
        val imageProtectedFromIdx = protectedBoundary(messages, imageProtectRecentUserTextTurns)

        // [T-image-bytes-visible] Pass 0 — HISTORY IMAGES. Inline b64 images
        // are the single largest body inflator (a 600 KB screenshot becomes
        // an ~800 KB b64 string), and until this pass estimateBytes counted
        // them as ZERO, so both this gate and PostAnchorPrune were blind
        // while the body sailed past the 1 MB relay limit («запрос отклонен
        // шлюзом» / the disguised `sensitive_words_detected` rejection).
        // Elide the eldest oversized images OUTSIDE the small image shield
        // FIRST — they reclaim the most bytes per edit; tool_results go after.
        // ImageData becomes a Text stub; ToolResult keeps its text content and
        // only loses the image bytes (with a re-fetch hint when the path is
        // known). Images inside the freshest turns are left for the providers'
        // ImageBudget compression ladder — never silently deleted.
        data class ImageCandidate(val msgIdx: Int, val partIdx: Int, val bytes: Int)
        val imageCandidates = ArrayList<ImageCandidate>()
        for (mi in 0 until imageProtectedFromIdx) {
            val parts = messages[mi].contentParts
            for (pi in parts.indices) {
                val p = parts[pi]
                when (p) {
                    is AgentContentPart.ImageData ->
                        imageCandidates.add(ImageCandidate(mi, pi, inlineImageBytes(p.data)))
                    is AgentContentPart.ToolResult ->
                        p.imageData?.let {
                            imageCandidates.add(ImageCandidate(mi, pi, inlineImageBytes(it)))
                        }
                    else -> Unit
                }
            }
        }
        imageCandidates.sortByDescending { it.bytes }

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
        if (imageCandidates.isEmpty() && big.isEmpty() && small.isEmpty()) {
            return Report(messages, 0, 0, bytesBefore, bytesBefore)
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
        var elidedImages = 0

        // Images first (biggest reclaim per edit).
        for (c in imageCandidates) {
            if (running <= maxBodyBytes) break
            val partsList = editedParts.getOrPut(c.msgIdx) {
                messages[c.msgIdx].contentParts.toMutableList()
            }
            val original = partsList[c.partIdx]
            when (original) {
                is AgentContentPart.ImageData -> {
                    val stub = AgentContentPart.Text(imageElisionStub(original))
                    partsList[c.partIdx] = stub
                    running -= (c.bytes - utf8(stub.text))
                    elidedImages += 1
                }
                is AgentContentPart.ToolResult -> {
                    partsList[c.partIdx] = original.copy(imageData = null)
                    running -= c.bytes
                    elidedImages += 1
                }
                else -> Unit
            }
        }

        // Then oversize tool_results.
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

        if (elided == 0 && elidedImages == 0) {
            return Report(messages, 0, 0, bytesBefore, bytesBefore)
        }

        val out = ArrayList<LLMMessage>(messages.size)
        for (i in messages.indices) {
            val edited = editedParts[i]
            if (edited == null) out.add(messages[i])
            else out.add(messages[i].copy(contentParts = edited))
        }
        return Report(out, elided, elidedImages, bytesBefore, estimateBytes(out))
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

    /** Marker on an elided inline image so the model (and diagnostics) can see why. */
    const val IMAGE_ELIDED_PREFIX = "[image elided to fit request budget"

    /**
     * [T-image-bytes-visible] Images are elidable outside the last N
     * user-text turns — a MUCH smaller shield than [plan]'s
     * protectRecentUserTextTurns for tool_results (24 at the provider
     * boundary). Rationale: the text/tool shield exists to keep the live
     * thread's WORKING CONTEXT verbatim; but a screenshot from 4+ turns ago
     * is dead weight the model has already described in its own words, while
     * the same 24-turn shield left every history image untouchable and the
     * 1.31 MB body went out to be rejected as «sensitive words detected».
     * The freshest 3 turns (typically: current question + its screenshots)
     * stay inline verbatim — if even they overflow, the providers compress
     * them via the ImageBudget ladder instead of eliding.
     */
    const val DEFAULT_IMAGE_PROTECT_TURNS = 3

    /**
     * [T-image-bytes-visible] Serialized size of inline image bytes: providers
     * base64-inline them, so the wire cost is ~4/3 of the raw bytes — NOT the
     * ~1-4k "visual tokens" the context bar charges. Until this constant
     * existed, estimateBytes counted history images as ZERO and a 3-screenshot
     * tail (measured live: 1.07 MB of JPEG → ~1.4 MB of b64) sailed past every
     * byte gate into «запрос отклонен шлюзом».
     */
    fun inlineImageBytes(data: ByteArray): Int = (data.size * 4) / 3

    private fun imageElisionStub(part: AgentContentPart.ImageData): String {
        val kb = (inlineImageBytes(part.data) + 999) / 1000
        val where = part.linuxPath?.let { " — re-fetch with read_image: $it" } ?: ""
        return "$IMAGE_ELIDED_PREFIX — ${kb} KB inline image removed$where]"
    }

    private fun isAlreadyReduced(content: String): Boolean =
        content.startsWith(ContextOffload.OFFLOADED_PREFIX) || content.startsWith(ELIDED_PREFIX)

    private fun elisionPlaceholder(part: AgentContentPart.ToolResult): String {
        val n = part.content.length
        val where = part.imageLinuxPath?.let { "; original at $it (re-fetch with read_image)" }
            ?: "; full output remains in session history"
        return "$ELIDED_PREFIX — ${part.name}, $n chars$where]"
    }

    /**
     * Estimated serialized body size: UTF-8 bytes of every text-bearing field.
     *
     * [T-image-bytes-visible] Inline images count at their BASE64 wire size
     * (~4/3 of raw bytes). They used to count as zero with a comment pointing
     * at ImageBudget — but ImageBudget's request cap is 25 MB, an order of
     * magnitude above the ~1 MB nginx `client_max_body_size` the relays
     * actually enforce, so it never fired for the 3-screenshot tail that
     * produced the live «запрос отклонен шлюзом» + 1.31 MB body. The body
     * budget is measured HERE, at the boundary that owns it.
     */
    fun estimateBytes(messages: List<LLMMessage>): Int {
        var total = 0
        for (msg in messages) {
            total += utf8(msg.content)
            total += msg.reasoningContent?.let { utf8(it) } ?: 0
            for (part in msg.contentParts) {
                total += when (part) {
                    is AgentContentPart.Text -> utf8(part.text)
                    is AgentContentPart.ToolUse -> utf8(part.input.toString())
                    is AgentContentPart.ToolResult -> {
                        utf8(part.content) +
                            (part.imageData?.let { inlineImageBytes(it) } ?: 0)
                    }
                    is AgentContentPart.ImageData -> inlineImageBytes(part.data)
                }
            }
        }
        return total
    }

    private fun utf8(s: String): Int = s.toByteArray(Charsets.UTF_8).size
}
