package com.openminis.app.data

import java.security.MessageDigest

/**
 * [T-canon-persistence] Pinned-canon store: the ALWAYS-injected circuit of
 * the memory system.
 *
 * Problem being fixed (verified live): a user fact said in chat lives ONLY
 * in the transcript. memory_write could only write to today's daily log,
 * which the system prompt demotes to "background context, not standing
 * instructions" and which drops out of the auto-inject window after 3
 * days. Compaction folds the original phrasing into a lossy summary. Net:
 * "запомни X" structurally cannot survive.
 *
 * Two-circuit design (instruction vs learning, as in Cursor Rules /
 * Claude CLAUDE.md / Letta core blocks):
 *   - CORE (this file):   small, pinned, injected EVERY turn as standing
 *     instructions. Written ONLY through an explicit gate — a deterministic
 *     classifier must see an explicit memorize/rule signal in the user's
 *     own words. The model cannot self-promote anything here.
 *   - DAILY (unchanged):  episode log, background framing, 3-file window.
 *
 * Storage: CANON.md next to GLOBAL.md, plain Markdown, human-editable in
 * Settings → Memory (no new UI needed — listAllFiles already surfaces every
 * .md in the memory dir). Entry format:
 *
 * <!-- canon:c-a1b2c3d4e5 -->
 * id: c-a1b2c3d4e5
 * type: preference
 * status: active
 * pin: true
 * source: user_explicit
 * created: 2026-09-08
 * supersedes: c-old0id9z8
 * text: Отвечай по-русски, коротко, без воды.
 *
 * Parser is hand-edit tolerant: unknown lines are ignored, missing fields
 * get defaults, broken blocks are skipped. serialize(parse(x)) is NOT
 * promised to be byte-identical (hand edits normalize away) but entry
 * semantics round-trip.
 *
 * Pure logic, no Context/File — unit-testable as-is (MemoryCanonTest).
 */
object MemoryCanon {

    data class CanonEntry(
        val id: String,
        val type: String,          // preference | constraint | decision | fact
        val status: String,        // active | superseded | revoked
        val pin: Boolean,
        val source: String,        // user_explicit | user_confirmed
        val created: String,       // yyyy-MM-dd
        val supersedes: String?,   // id of the entry this one replaced
        val text: String,
    )

    /** Char budget for the injected canon fragment (visible to the model). */
    const val MAX_FRAGMENT_CHARS = 4096

    private val MARKER = Regex("<!-- canon:(c-[a-z0-9]+) -->")

    // -- IDs ---------------------------------------------------------------

    /** Lowercase, strip everything that isn't a letter/digit — for dedup ids. */
    fun normalizeForId(text: String): String =
        text.lowercase().replace(Regex("[^а-яёa-z0-9]"), "")

    /** Deterministic content id: same fact → same id (dedup across flushes). */
    fun idFor(text: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(normalizeForId(text).toByteArray(Charsets.UTF_8))
        return "c-" + digest.joinToString("") { "%02x".format(it) }.take(10)
    }

    // -- Parse / serialize ---------------------------------------------------

    fun parse(fileText: String): List<CanonEntry> {
        val out = mutableListOf<CanonEntry>()
        val matches = MARKER.findAll(fileText).toList()
        for ((i, m) in matches.withIndex()) {
            val bodyStart = m.range.last + 1
            val bodyEnd = if (i + 1 < matches.size) matches[i + 1].range.first else fileText.length
            val body = fileText.substring(bodyStart, bodyEnd).trim()
            if (body.isEmpty()) continue
            var id = m.groupValues[1]
            var type = "fact"
            var status = "active"
            var pin = true
            var source = "user_explicit"
            var created = ""
            var supersedes: String? = null
            var text = ""
            val lines = body.lines()
            var textStarted = false
            val textLines = mutableListOf<String>()
            for (line in lines) {
                if (textStarted) { textLines.add(line); continue }
                val kv = Regex("^(id|type|status|pin|source|created|supersedes):\\s*(.*)$").find(line)
                    ?: continue
                val v = kv.groupValues[2].trim()
                when (kv.groupValues[1]) {
                    "id" -> if (v.isNotEmpty()) id = v
                    "type" -> type = v.ifEmpty { "fact" }
                    "status" -> status = v.ifEmpty { "active" }
                    "pin" -> pin = v.lowercase() != "false"
                    "source" -> source = v.ifEmpty { "user_explicit" }
                    "created" -> created = v
                    "supersedes" -> supersedes = v.ifEmpty { null }
                }
                if (line.startsWith("text:")) {
                    textStarted = true
                    val first = line.removePrefix("text:").trim()
                    if (first.isNotEmpty()) textLines.add(first)
                }
            }
            // Hand-edited block without a `text:` key: treat the whole body
            // after the key-lines as the text (better than dropping it).
            if (!textStarted) {
                val leftovers = lines.filter { l ->
                    !Regex("^(id|type|status|pin|source|created|supersedes|text):").containsMatchIn(l)
                }
                textLines.addAll(leftovers)
            }
            text = textLines.joinToString("\n").trim()
            if (text.isEmpty()) continue
            out.add(CanonEntry(id, type, status, pin, source, created, supersedes, text))
        }
        return out
    }

    fun serializeEntry(e: CanonEntry): String = buildString {
        append("<!-- canon:").append(e.id).append(" -->\n")
        append("id: ").append(e.id).append('\n')
        append("type: ").append(e.type).append('\n')
        append("status: ").append(e.status).append('\n')
        append("pin: ").append(e.pin).append('\n')
        append("source: ").append(e.source).append('\n')
        append("created: ").append(e.created).append('\n')
        append("supersedes: ").append(e.supersedes ?: "").append('\n')
        append("text: ").append(e.text.replace("\n", " ").trim()).append('\n')
    }

    fun serialize(entries: List<CanonEntry>): String =
        entries.joinToString("\n") { serializeEntry(it) }

    /**
     * Append a new entry (newest first) and apply supersede semantics: the
     * replaced entry's status flips to "superseded". Returns the new file
     * text and the finalized entry (id filled if empty).
     */
    fun append(fileText: String, entry: CanonEntry): Pair<String, CanonEntry> {
        val id = entry.id.ifEmpty { idFor(entry.text) }
        val finalized = entry.copy(id = id)
        val existing = parse(fileText).map {
            if (it.id == finalized.supersedes && it.status == "active") it.copy(status = "superseded")
            else it
        }
        val newBody = serialize(finalized) + "\n" +
            serialize(existing.filter { it.status != "revoked" })
        return newBody to finalized
    }

    // -- Gate classifier -----------------------------------------------------

    /**
     * Deterministic explicit-memory-signal classifier. MUST be conservative:
     * a false positive pins garbage into the standing-instructions core (the
     * exact failure this system exists to prevent); a false negative is
     * caught later by the pre-compact flush. RU boundaries use explicit
     * letter-class lookarounds because Java \b is ASCII-only and never
     * matches around Cyrillic.
     */
    private val SIGNALS: List<Regex> = listOf(
        Regex("(?<![а-яёА-ЯЁ])запомни(те|ть)?(?![а-яёА-ЯЁ])"),
        Regex("(?<![а-яёА-ЯЁ])запиши(те)?(?![а-яёА-ЯЁ])(?=.{0,30}(это|в память|правил|факт))"),
        Regex("(?<![а-яёА-ЯЁ])занеси(те)?|внеси(те)?(?![а-яёА-ЯЁ])(?=.{0,20}в )"),
        Regex("(?<![а-яёА-ЯЁ])(всегда|никогда)(?![а-яёА-ЯЁ])(?=.{0,20}(отвеча|дела|использ|пиши|спрашива|проверя|создава|удаля|задава|выбира))"),
        Regex("\\bremember\\s+(this|that)\\b|\\bnote\\s+(this|that)\\s+down\\b|\\bkeep\\s+in\\s+mind\\s+that\\b"),
        Regex("\\b(always|never)\\s+(answer|reply|use|write|ask|check|create|delete|choose|prefer)\\b"),
    )

    fun isExplicitMemorySignal(text: String): Boolean =
        SIGNALS.any { it.containsMatchIn(text) }

    /**
     * Extract the fact text from a signal-bearing user message: strip a
     * leading signal phrase, cap the length. Coarse by design — these are
     * the user's own verbatim words, recorded as-said.
     */
    fun extractFactText(userText: String, maxChars: Int = 400): String {
        var t = userText.trim()
        t = Regex("^((?i)запомни(те|ть)?|запишите|remember\\s+(this|that)|note\\s+(this|that)\\s+down)[,:!\\s]*").replace(t, "")
        t = Regex("^что\\s+").replace(t, "")
        return t.take(maxChars).trim()
    }

    // -- Injection -----------------------------------------------------------

    /**
     * Standing-instructions fragment. Only active+pin entries, newest first,
     * budget-capped with a visible counter (Letta-style observability).
     */
    fun buildFragment(entries: List<CanonEntry>, budget: Int = MAX_FRAGMENT_CHARS): String? {
        val active = entries.filter { it.status == "active" && it.pin && it.text.isNotBlank() }
        if (active.isEmpty()) return null
        val lines = mutableListOf<String>()
        var used = 0
        var omitted = 0
        for (e in active) {
            val line = "- [${e.id}] ${e.text.replace('\n', ' ').trim()}"
            val cost = line.length + 1
            if (used + cost > budget) { omitted++; continue }
            lines.add(line)
            used += cost
        }
        if (lines.isEmpty()) return null
        return buildString {
            append("Pinned canon (CANON.md — user-confirmed standing rules and facts). ")
            append("These ARE standing instructions — obey them. The only thing that overrides ")
            append("them is the user's explicit instruction in the current conversation:\n")
            append(lines.joinToString("\n"))
            append("\n(canon: ").append(used).append('/').append(budget).append(" chars")
            if (omitted > 0) append(", ").append(omitted).append(" older omitted — memory_get to search")
            append(')')
        }
    }
}
