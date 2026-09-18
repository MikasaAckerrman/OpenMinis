package com.openminis.app.data

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
 * So the filler is stripped and any dropped identifier is re-appended verbatim
 * from the source transcript. The result is never worse than what the model
 * returned. Used by the `/compact` (AI) path — see
 * [com.openminis.app.ui.chat.ChatViewModel.compactAll].
 *
 * Pure logic, no Android — unit-tested.
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
     * Identifiers from [transcript] that a resuming agent cannot re-derive:
     * long file paths, URLs and hashes only, so the appendix never fills with
     * false positives.
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
