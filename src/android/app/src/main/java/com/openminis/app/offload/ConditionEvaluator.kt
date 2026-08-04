package com.openminis.app.offload

import com.openminis.app.data.model.Handoff

/**
 * [T-agent-graph-router] Evaluates `CONDITIONAL` edge conditions against a
 * predecessor's handoff.
 *
 * Deliberately a tiny hand-written evaluator rather than an expression engine.
 * Conditions live in user-authored graph JSON, so every operator here has to be
 * something a person will actually type correctly; a full expression language
 * would mostly add ways to write a condition that silently never matches.
 *
 * Supported forms (whitespace-insensitive, case-insensitive keys):
 *
 *   complexity >= L2        complexity <= L1        complexity == L3
 *   status == COMPLETE      status != BLOCKED
 *   verdict == APPROVED     verdict contains APPROVED
 *   deliverables contains <substring>
 *   always                  (or empty/absent — edge is unconditional)
 *
 * Multiple clauses joined by `&&` — all must hold. `||` is NOT supported on
 * purpose: an OR across edges is expressed by having two edges.
 *
 * An UNPARSEABLE condition returns false and is reported, not silently treated
 * as true. A typo that quietly opens a gate is worse than one that closes it:
 * the closed gate shows up as a skipped node in the trace, the open one lets
 * the wrong agents run.
 */
internal object ConditionEvaluator {

    /** Complexity ladder. Ordinal ordering is what `>=` compares. */
    enum class Complexity { L0, L1, L2, L3, L4 }

    data class Result(val matched: Boolean, val explanation: String)

    /**
     * The orchestrator states the level as `COMPLEXITY: L2` on a line of its
     * handoff. Look in deliverables first (where the prompt asks for it), then
     * fall back to the other free-text fields.
     */
    fun extractComplexity(handoff: Handoff): Complexity? {
        val haystack = buildList {
            addAll(handoff.deliverables)
            addAll(handoff.successCriteria)
            add(handoff.nextAction)
        }
        val re = Regex("""COMPLEXITY\s*[:=]\s*(L[0-4])""", RegexOption.IGNORE_CASE)
        for (line in haystack) {
            val m = re.find(line) ?: continue
            return runCatching {
                Complexity.valueOf(m.groupValues[1].uppercase())
            }.getOrNull()
        }
        return null
    }

    fun evaluate(condition: String?, handoff: Handoff): Result {
        if (condition.isNullOrBlank()) return Result(true, "unconditional")

        val clauses = condition.split("&&").map { it.trim() }.filter { it.isNotEmpty() }
        if (clauses.isEmpty()) return Result(true, "unconditional")

        val notes = mutableListOf<String>()
        for (clause in clauses) {
            val r = evaluateClause(clause, handoff)
            notes.add("${r.explanation}")
            if (!r.matched) {
                return Result(false, notes.joinToString("; "))
            }
        }
        return Result(true, notes.joinToString("; "))
    }

    private fun evaluateClause(clause: String, handoff: Handoff): Result {
        val c = clause.trim()
        if (c.equals("always", ignoreCase = true) || c == "true") {
            return Result(true, "always")
        }

        // complexity >= L2 / <= L1 / == L3 / != L0
        Regex("""complexity\s*(>=|<=|==|!=|>|<)\s*(L[0-4])""", RegexOption.IGNORE_CASE)
            .find(c)?.let { m ->
                val op = m.groupValues[1]
                val want = runCatching {
                    Complexity.valueOf(m.groupValues[2].uppercase())
                }.getOrNull()
                    ?: return Result(false, "unparseable complexity level in '$c'")
                val got = extractComplexity(handoff)
                    ?: return Result(
                        false,
                        "condition '$c' needs a COMPLEXITY marker, but the handoff has none",
                    )
                val ok = when (op) {
                    ">=" -> got.ordinal >= want.ordinal
                    "<=" -> got.ordinal <= want.ordinal
                    ">" -> got.ordinal > want.ordinal
                    "<" -> got.ordinal < want.ordinal
                    "==" -> got == want
                    "!=" -> got != want
                    else -> false
                }
                return Result(ok, "complexity $got $op $want -> $ok")
            }

        // status == COMPLETE / != BLOCKED
        Regex("""status\s*(==|!=)\s*([A-Z_]+)""", RegexOption.IGNORE_CASE)
            .find(c)?.let { m ->
                val op = m.groupValues[1]
                val want = m.groupValues[2].uppercase()
                val got = handoff.status.name
                val ok = if (op == "==") got == want else got != want
                return Result(ok, "status $got $op $want -> $ok")
            }

        // verdict == APPROVED / verdict contains APPROVED
        Regex("""verdict\s*(==|contains)\s*'?([A-Za-z_ ]+)'?""", RegexOption.IGNORE_CASE)
            .find(c)?.let { m ->
                val want = m.groupValues[2].trim().replace(' ', '_').uppercase()
                val corpus = (handoff.deliverables + handoff.nextAction)
                    .joinToString(" ")
                    .replace(' ', '_')
                    .uppercase()
                val ok = corpus.contains(want)
                return Result(ok, "verdict contains $want -> $ok")
            }

        // deliverables contains <substring>
        Regex("""deliverables\s+contains\s+'?(.+?)'?$""", RegexOption.IGNORE_CASE)
            .find(c)?.let { m ->
                val want = m.groupValues[1].trim()
                val ok = handoff.deliverables.any { it.contains(want, ignoreCase = true) }
                return Result(ok, "deliverables contains '$want' -> $ok")
            }

        // Unknown syntax: fail closed and say so.
        return Result(false, "unrecognised condition syntax: '$c'")
    }

    /**
     * Static check for graph validation: does this condition parse at all?
     * Lets GraphsCollection reject a typo at save time instead of at 3am
     * halfway through a run.
     */
    fun isSyntaxValid(condition: String?): Boolean {
        if (condition.isNullOrBlank()) return true
        val clauses = condition.split("&&").map { it.trim() }.filter { it.isNotEmpty() }
        if (clauses.isEmpty()) return true
        val patterns = listOf(
            Regex("""^(always|true)$""", RegexOption.IGNORE_CASE),
            Regex("""^complexity\s*(>=|<=|==|!=|>|<)\s*L[0-4]$""", RegexOption.IGNORE_CASE),
            Regex("""^status\s*(==|!=)\s*[A-Z_]+$""", RegexOption.IGNORE_CASE),
            Regex("""^verdict\s*(==|contains)\s*'?[A-Za-z_ ]+'?$""", RegexOption.IGNORE_CASE),
            Regex("""^deliverables\s+contains\s+.+$""", RegexOption.IGNORE_CASE),
        )
        return clauses.all { cl -> patterns.any { it.matches(cl) } }
    }
}
