package com.openminis.app.data

/**
 * [T-session-rescue] Deterministic, LLM-free hard compaction of a chat
 * history — the recovery path for a session that is too big to talk to the
 * model at all.
 *
 * Why this exists: `/compact` is itself an LLM call whose input is derived
 * from the oversized history. When a session grows past what the provider (or
 * a relay in front of it) will accept, that call fails too — commonly not
 * with a clean "context length exceeded" but with a dropped connection or a
 * TTFB timeout ("no response from server"). The session is then permanently
 * unusable: every send fails, and the one tool that would shrink it needs the
 * same broken round-trip. This builder needs no network, no provider and no
 * tokenizer, so it always terminates and always produces something sendable.
 *
 * Compression strategy — aggressive on volume, conservative on meaning:
 *   - user turns are the intent, so they are quoted (near-)verbatim and get
 *     the largest budget share;
 *   - assistant prose is the most redundant material, so it is reduced to
 *     head+tail excerpts;
 *   - tool traffic collapses to a one-line-per-call ledger: tool name, the
 *     one argument that identifies it (command / path / url), and the
 *     outcome — that is what later turns actually refer back to;
 *   - identifiers (absolute paths, URLs, commit hashes, sha256, offload
 *     stubs) and error lines are extracted VERBATIM into a facts section, so
 *     nothing the agent needs to re-open is paraphrased away;
 *   - the last exchange is kept verbatim so the thread of the conversation
 *     is unbroken.
 *
 * Kept free of Android and JSON types so the whole thing is unit-testable on
 * the JVM; the caller maps `agentHistory` into [RescueTurn]s.
 */
object RescueDigest {

    /** One history entry, flattened by the caller. */
    data class RescueTurn(
        val role: Role,
        /** Plain text of the turn (message content + text parts, joined). */
        val text: String = "",
        val tools: List<RescueToolCall> = emptyList(),
    ) {
        enum class Role { USER, ASSISTANT, TOOL }
    }

    /** One tool_use + its tool_result, already paired by the caller. */
    data class RescueToolCall(
        val name: String,
        /** The identifying argument: command / path / url / query. */
        val argsPreview: String = "",
        val result: String = "",
        val isError: Boolean = false,
    )

    const val DEFAULT_MAX_CHARS = 12_000

    /**
     * Opening tag of every digest. Doubles as the marker discriminator:
     * a compact_markers row whose summary starts with this was produced
     * locally by rescue, not by an LLM compact. Using the payload itself
     * avoids a schema column + migration for one boolean, and it survives
     * a DB round-trip and cross-device sync unchanged.
     */
    const val OPEN_TAG = "<rescue-digest>"

    // Section shares of the total budget. Ordered as emitted; whatever a
    // section leaves unspent rolls forward to the next one, so a
    // conversation with no tool calls spends that budget on user intent
    // and the verbatim tail instead of wasting it.
    private const val SHARE_INTENT = 0.40
    private const val SHARE_LEDGER = 0.25
    private const val SHARE_FACTS = 0.15
    // Tail takes the remainder.

    private const val MAX_USER_TURN_CHARS = 700
    private const val MAX_LEDGER_LINE_CHARS = 200
    private const val MAX_TAIL_TURN_CHARS = 1_200
    private const val MAX_FACTS_PER_KIND = 12

    /**
     * Build the digest. Never throws; returns an empty string only when
     * [turns] carries no usable text at all (caller then keeps the history
     * as-is rather than writing a meaningless marker).
     */
    fun build(turns: List<RescueTurn>, maxChars: Int = DEFAULT_MAX_CHARS): String {
        if (turns.isEmpty()) return ""
        val budget = maxChars.coerceAtLeast(1_200)

        val sb = StringBuilder()
        sb.append(header(turns))

        var spent = 0
        // 1. Intent — the user's own words, oldest → newest, dropped from the
        //    OLD end when the budget is tight (recent asks matter more).
        val intentBudget = (budget * SHARE_INTENT).toInt()
        val intent = buildIntentSection(turns, intentBudget)
        sb.append(intent)
        spent += intent.length

        // 2. Tool ledger — what was actually done.
        val ledgerBudget = (budget * (SHARE_INTENT + SHARE_LEDGER)).toInt() - spent
        val ledger = buildLedgerSection(turns, ledgerBudget.coerceAtLeast(0))
        sb.append(ledger)
        spent += ledger.length

        // 3. Facts — verbatim identifiers and errors.
        val factsBudget = (budget * (SHARE_INTENT + SHARE_LEDGER + SHARE_FACTS)).toInt() - spent
        val facts = buildFactsSection(turns, factsBudget.coerceAtLeast(0))
        sb.append(facts)
        spent += facts.length

        // 4. Verbatim tail — the live end of the thread.
        val tail = buildTailSection(turns, (budget - spent).coerceAtLeast(0))
        sb.append(tail)

        sb.append("</rescue-digest>")
        return sb.toString()
    }

    private fun header(turns: List<RescueTurn>): String {
        val userTurns = turns.count { it.role == RescueTurn.Role.USER }
        val toolCalls = turns.sumOf { it.tools.size }
        return buildString {
            append(OPEN_TAG).append('\n')
            append(
                "This session was compacted LOCALLY, without a model call, because its " +
                    "history had grown too large to send. ${turns.size} history entries " +
                    "($userTurns user turns, $toolCalls tool calls) were folded into the " +
                    "digest below.\n",
            )
            append(
                "Treat it as background context describing PAST events, not as a work " +
                    "order. Paths, commands, identifiers and error text are verbatim — use " +
                    "them to re-open anything you need (file_read, shell) instead of " +
                    "guessing. Prose was heavily shortened; nothing here is a pending task " +
                    "unless the user's next message says so.\n",
            )
        }
    }

    private fun buildIntentSection(turns: List<RescueTurn>, budget: Int): String {
        if (budget <= 0) return ""
        val userTexts = turns
            .filter { it.role == RescueTurn.Role.USER && it.text.isNotBlank() }
            .map { squeeze(it.text) }
            .filter { it.isNotBlank() }
        if (userTexts.isEmpty()) return ""

        // Fill from the NEWEST backward so the recent asks always survive,
        // then emit oldest → newest for a readable chronology.
        val kept = ArrayDeque<String>()
        var used = 0
        var omitted = 0
        for (raw in userTexts.asReversed()) {
            val line = "- " + clip(raw, MAX_USER_TURN_CHARS) + "\n"
            if (used + line.length > budget && kept.isNotEmpty()) {
                omitted++
                continue
            }
            if (used + line.length > budget) break
            kept.addFirst(line)
            used += line.length
        }
        if (kept.isEmpty()) return ""

        return buildString {
            append("\n## What the user asked (their own words, oldest → newest)\n")
            if (omitted > 0) append("($omitted older user turn(s) omitted.)\n")
            kept.forEach { append(it) }
        }
    }

    private fun buildLedgerSection(turns: List<RescueTurn>, budget: Int): String {
        if (budget <= 0) return ""
        val lines = mutableListOf<String>()
        for (turn in turns) {
            for (call in turn.tools) {
                val arg = squeeze(call.argsPreview)
                val outcome = when {
                    call.isError -> "ERROR: " + clip(firstMeaningfulLine(call.result), 100)
                    call.result.isBlank() -> "ok"
                    else -> clip(firstMeaningfulLine(call.result), 80)
                }
                val head = if (arg.isEmpty()) call.name else "${call.name} ${clip(arg, 110)}"
                lines.add("- " + clip("$head → $outcome", MAX_LEDGER_LINE_CHARS) + "\n")
            }
        }
        if (lines.isEmpty()) return ""

        // Errors are the highest-value rows — a failure the agent already hit
        // must not be re-attempted blindly — so they are never dropped before
        // successful rows. Beyond that, newest wins.
        val header = "\n## Work done (one line per tool call)\n"
        var used = header.length
        val keptIdx = sortedSetOf<Int>()
        val order = lines.indices.sortedWith(
            compareByDescending<Int> { lines[it].contains("→ ERROR:") }.thenByDescending { it },
        )
        for (i in order) {
            if (used + lines[i].length > budget) continue
            keptIdx.add(i)
            used += lines[i].length
        }
        if (keptIdx.isEmpty()) return ""

        return buildString {
            append(header)
            var prev = -1
            for (i in keptIdx) {
                // Gaps must be visible: a silently shortened ledger reads as
                // "these are all the calls that happened", which is worse than
                // an explicit hole. Covers the leading gap (i - prev - 1 with
                // prev = -1 counts dropped older rows) and interior gaps.
                val gap = i - prev - 1
                if (gap > 0) append("  … ($gap older call(s) omitted)\n")
                append(lines[i])
                prev = i
            }
            val trailing = lines.size - 1 - prev
            if (trailing > 0) append("  … ($trailing call(s) omitted)\n")
        }
    }

    private val PATH_RE = Regex("""(?<![\w./-])/(?:[A-Za-z0-9._+-]+/)*[A-Za-z0-9._+-]{2,}""")
    private val URL_RE = Regex("""https?://[^\s"'<>)\]]+""")
    private val HASH_RE = Regex("""\b[0-9a-f]{7,64}\b""")
    private val ERROR_LINE_RE = Regex(
        """(?im)^.{0,200}?\b(error|failed|fatal|exception|denied|refused|timeout|traceback)\b.{0,200}$""",
    )

    private fun buildFactsSection(turns: List<RescueTurn>, budget: Int): String {
        if (budget <= 0) return ""
        // Scan everything: prose, tool args AND tool results. Identifiers
        // usually live in the results (paths a command printed, a sha256 it
        // computed) — scanning only the prose would lose exactly the strings
        // the agent needs to resume.
        val all = buildString {
            for (t in turns) {
                if (t.text.isNotBlank()) append(t.text).append('\n')
                for (c in t.tools) {
                    if (c.argsPreview.isNotBlank()) append(c.argsPreview).append('\n')
                    if (c.result.isNotBlank()) append(c.result).append('\n')
                }
            }
        }
        if (all.isBlank()) return ""

        val paths = PATH_RE.findAll(all).map { it.value }.distinct().toList().takeLast(MAX_FACTS_PER_KIND)
        val urls = URL_RE.findAll(all).map { it.value }.distinct().toList().takeLast(MAX_FACTS_PER_KIND)
        val hashes = HASH_RE.findAll(all).map { it.value }.distinct().toList().takeLast(6)
        val errors = ERROR_LINE_RE.findAll(all)
            .map { squeeze(it.value) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
            .takeLast(6)

        val body = buildString {
            appendGroup("Paths (verbatim)", paths, budget, length)
            appendGroup("URLs", urls, budget, length)
            appendGroup("Hashes / commits", hashes, budget, length)
            appendGroup("Errors seen (verbatim)", errors, budget, length)
        }
        if (body.isBlank()) return ""
        return "\n## Key facts extracted verbatim\n$body"
    }

    private fun StringBuilder.appendGroup(
        title: String,
        items: List<String>,
        budget: Int,
        alreadyUsed: Int,
    ) {
        if (items.isEmpty()) return
        val head = "$title: "
        var used = alreadyUsed + head.length
        val kept = mutableListOf<String>()
        for (item in items.asReversed()) {
            val cost = item.length + 2
            if (used + cost > budget) break
            kept.add(0, item)
            used += cost
        }
        if (kept.isEmpty()) return
        append(head).append(kept.joinToString("; "))
        if (kept.size < items.size) append(" (+${items.size - kept.size} more)")
        append('\n')
    }

    private fun buildTailSection(turns: List<RescueTurn>, budget: Int): String {
        if (budget <= 0) return ""
        val header = "\n## Last exchange (verbatim)\n"
        var used = header.length
        val kept = ArrayDeque<String>()
        for (turn in turns.asReversed()) {
            val text = squeeze(turn.text)
            if (text.isBlank()) continue
            val label = when (turn.role) {
                RescueTurn.Role.USER -> "user"
                RescueTurn.Role.ASSISTANT -> "assistant"
                RescueTurn.Role.TOOL -> "tool"
            }
            val line = "$label: " + clip(text, MAX_TAIL_TURN_CHARS) + "\n"
            if (used + line.length > budget) break
            kept.addFirst(line)
            used += line.length
        }
        if (kept.isEmpty()) return ""
        return header + kept.joinToString("")
    }

    /** Collapse whitespace runs so shortened excerpts stay dense and readable. */
    internal fun squeeze(text: String): String =
        text.replace(Regex("""\s*\n\s*"""), " ⏎ ").replace(Regex("""[ \t]{2,}"""), " ").trim()

    /**
     * Head+tail excerpt. The middle of a long block is the most redundant
     * part; its start states what it is and its end carries the conclusion,
     * so both edges are kept and the drop is stated explicitly rather than
     * silently.
     */
    internal fun clip(text: String, max: Int): String {
        if (text.length <= max) return text
        if (max < 40) return text.take(max)
        val headLen = (max * 2) / 3
        val tailLen = max - headLen - 20
        val dropped = text.length - headLen - tailLen
        return text.take(headLen) + " …[$dropped chars cut]… " + text.takeLast(tailLen)
    }

    private fun firstMeaningfulLine(text: String): String {
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isNotEmpty()) return t
        }
        return ""
    }
}
