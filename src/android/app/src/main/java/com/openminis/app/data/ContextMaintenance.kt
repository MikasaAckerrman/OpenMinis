package com.openminis.app.data

/**
 * [T-context-maintenance] Decides, per user send, what context work to do —
 * so a session never drifts into the state where it cannot talk to the model.
 *
 * The user's ask was "compress on every request, and do a proper compression
 * every ~5 requests, without stalling and without wasting tokens". That maps
 * onto two tiers with very different costs:
 *
 *  - LIGHT, every turn: local only. Offload oversized tool payloads to disk,
 *    drop duplicate/stale bulk. Costs zero tokens and no latency worth
 *    measuring, so there is no reason to ration it.
 *  - FULL, periodically: an LLM summarisation pass. Costs a real request, so
 *    it is rationed on THREE independent gates — turn cadence, pressure floor,
 *    and cooldown — instead of just a counter. A fixed "every 5th turn" alone
 *    would fire on a 3K-token session (pure waste) and would still be 4 turns
 *    away when a single huge tool result blows the window.
 *
 * The pressure floor is what makes this cheap: below it, compaction is
 * skipped entirely no matter how many turns have passed, because summarising
 * a small history costs more tokens than it saves.
 *
 * Pure logic, no Android — the whole decision matrix is unit-tested.
 */
object ContextMaintenance {

    enum class Action {
        /** Nothing to do: small session, or a gate said no. */
        NONE,

        /** Local-only pass: offload big tool payloads. Free. */
        LIGHT,

        /** LLM summarisation pass. Costs one request. */
        FULL,

        /**
         * Too big to summarise via the model at all — go straight to the local
         * digest ([RescueDigest]). Reached when pressure is past the point
         * where a summarisation request would itself be refused or dropped.
         */
        RESCUE,
    }

    /** Default: a full pass every 5th user turn, matching the user's ask. */
    const val DEFAULT_FULL_EVERY_N_TURNS = 5

    /**
     * Below this fraction of the window, do nothing but the free local pass.
     * 0.35 of a 200K window is ~70K tokens — a session that large genuinely
     * benefits from folding; anything smaller does not repay the request.
     */
    const val FULL_PRESSURE_FLOOR = 0.35

    /**
     * Above this fraction, an LLM compact is no longer trustworthy: its own
     * request carries a transcript derived from this history, and providers
     * start dropping such bodies rather than answering. Skip straight to the
     * local digest, which cannot fail.
     */
    const val RESCUE_PRESSURE_CEILING = 0.85

    /** Don't run two full passes within this window, however many turns pass. */
    const val FULL_COOLDOWN_MS = 3 * 60 * 1000L

    /**
     * @param userTurnsSinceFull user sends since the last successful full pass
     * @param contextTokens last reported context size (0 = unknown)
     * @param contextWindow model's window (0 = unknown)
     * @param compactSupported false on small-window tiers where
     *        [ContextPolicy] disables compaction entirely
     * @param alreadyRescued true when the payload is ALREADY a local rescue
     *        digest. Rescuing a rescue digest cannot shrink anything (the
     *        history it would fold is the digest itself), so without this flag
     *        a session whose digest still reads as "over the ceiling" would
     *        rescue on every single send, writing a marker per message. When
     *        set, the ceiling branch is skipped and normal gating applies.
     */
    fun decide(
        userTurnsSinceFull: Int,
        contextTokens: Int,
        contextWindow: Int,
        compactSupported: Boolean,
        isCompacting: Boolean,
        lastFullAtMs: Long,
        nowMs: Long,
        fullEveryNTurns: Int = DEFAULT_FULL_EVERY_N_TURNS,
        alreadyRescued: Boolean = false,
    ): Action {
        if (isCompacting) return Action.NONE
        // Unknown pressure → the light pass is still safe (it is local and
        // threshold-driven itself), but nothing that costs tokens.
        if (contextWindow <= 0 || contextTokens <= 0) return Action.LIGHT

        val fraction = contextTokens.toDouble() / contextWindow.toDouble()

        // Ceiling first: past it, cadence and cooldown are irrelevant — an LLM
        // compact would likely fail, and failing is what left the user stuck.
        if (fraction >= RESCUE_PRESSURE_CEILING && !alreadyRescued) return Action.RESCUE

        if (!compactSupported) return Action.LIGHT
        if (fraction < FULL_PRESSURE_FLOOR) return Action.LIGHT
        if (nowMs - lastFullAtMs < FULL_COOLDOWN_MS) return Action.LIGHT

        val cadence = fullEveryNTurns.coerceAtLeast(1)
        // Cadence is a floor, not a schedule: heavy pressure shouldn't wait for
        // turn 5. Halfway between floor and ceiling, run regardless of cadence.
        val urgent = fraction >= (FULL_PRESSURE_FLOOR + RESCUE_PRESSURE_CEILING) / 2
        return if (urgent || userTurnsSinceFull >= cadence) Action.FULL else Action.LIGHT
    }
}

/**
 * [T-context-maintenance] Post-processing that makes an LLM summary
 * trustworthy instead of merely short.
 *
 * A summarisation model reliably does two things that hurt an agent: it writes
 * filler ("I will now proceed to explain that..."), and it paraphrases exact
 * strings — turning `/var/minis/shared/x/app.apk` into "the APK in the shared
 * folder" and dropping hashes as noise. The first wastes the context the
 * summary was supposed to save; the second destroys the summary's only
 * irreplaceable content, because prose can be re-derived while an exact path
 * cannot.
 *
 * Rejecting such a summary outright (as the rescue path does) is wrong here:
 * `/compact`'s summary is all the caller has. So instead the filler is
 * stripped and any dropped identifier is re-appended verbatim from the source
 * transcript. The result is never worse than what the model returned.
 */
object CompactQuality {

    /** Lines that carry no information for a resuming agent. */
    private val FILLER_PATTERNS = listOf(
        Regex("""(?i)^\s*(sure|certainly|of course|okay|ok)[,!.]\s*""" ),
        Regex("""(?i)^\s*here('s| is) (the|a|your) (summary|rewritten|compacted).*$"""),
        Regex("""(?i)^\s*(i|we) (will|'ll|am going to|have) (now )?(summariz|provid|present|explain).*$"""),
        Regex("""(?i)^\s*(as (an|your) ai|note that i|please note that).*$"""),
        Regex("""(?i)^\s*(in (summary|conclusion)|to summarize)[:,]\s*$"""),
        Regex("""(?i)^\s*(let me know if.*|feel free to.*|i hope this helps.*)$"""),
    )

    /** Strip conversational filler lines and collapse blank runs. */
    fun stripFiller(summary: String): String {
        val kept = summary.lines().filter { line ->
            val t = line.trim()
            if (t.isEmpty()) return@filter true
            FILLER_PATTERNS.none { it.containsMatchIn(t) }
        }
        // Collapse 3+ blank lines to one — filler removal leaves holes.
        return kept.joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    /**
     * Identifiers from [transcript] that a resuming agent cannot re-derive.
     * Same conservative shape as [RescueRefinement.verbatimFacts]: long and
     * unmistakable only, so the appendix never fills with false positives.
     */
    fun criticalFacts(transcript: String, limit: Int = 25): List<String> {
        val facts = LinkedHashSet<String>()
        Regex("""/(?:[A-Za-z0-9._+-]+/)+[A-Za-z0-9._+-]{2,}""").findAll(transcript)
            .map { it.value }
            .filter { it.length >= 12 }
            .forEach { facts.add(it) }
        Regex("""https?://[^\s"'<>)\]]+""").findAll(transcript)
            .map { it.value }
            .forEach { facts.add(it) }
        Regex("""\b[0-9a-f]{12,64}\b""").findAll(transcript)
            .map { it.value }
            .forEach { facts.add(it) }
        return facts.toList().takeLast(limit)
    }

    /**
     * Clean [summary], then re-attach any [criticalFacts] the model dropped.
     *
     * @param maxChars hard ceiling for the returned text; the appendix is
     *        truncated rather than allowed to blow the budget the caller
     *        already sized its context around.
     */
    fun polish(transcript: String, summary: String, maxChars: Int = 20_000): String {
        val cleaned = stripFiller(summary)
        val missing = criticalFacts(transcript).filter { !cleaned.contains(it) }
        if (missing.isEmpty()) return cleaned.take(maxChars)

        val header = "\n\n## Exact references preserved from the original (do not paraphrase)\n"
        val sb = StringBuilder(cleaned)
        sb.append(header)
        for (fact in missing) {
            val line = "- $fact\n"
            if (sb.length + line.length > maxChars) break
            sb.append(line)
        }
        return sb.toString().take(maxChars)
    }
}
