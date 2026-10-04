package com.openminis.app.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [T-hook-engine] User-authored hook rulesets, engine-side.
 *
 * A JSON ruleset intercepts tool calls. Format (backwards compatible with
 * the original tools/ToolHooks file — existing hooks.json keeps working):
 *
 * {
 *   "rules": [
 *     {"tool": "shell_execute", "match": "rm -rf", "action": "block",
 *      "message": "no rm -rf in this house"},
 *     {"tool": "file_write", "match": "\\.kt$", "action": "warn",
 *      "phase": "post", "message": "remember to run the formatter"}
 *   ]
 * }
 *
 * Phases:
 *  - "pre"  (default) — evaluated BEFORE execution. "block" refuses the
 *    call with the rule's message; "warn" prepends the message to the
 *    call's result.
 *  - "post" — evaluated AFTER execution; the message is returned to the
 *    model as feedback (the ZCode hook-feedback loop: auto-formatters,
 *    audit hints, review reminders). A "block" action in post phase makes
 *    no sense — the call already ran — and degrades to "warn".
 *
 * Matching: "match" is a regex against the raw lowercased arguments JSON,
 * deliberately raw so a rule can key on any field. The match input is
 * capped (default 2000 chars): user regexes run against MODEL-CONTROLLED
 * strings and a pathological pattern is an exponential blowup on the
 * calling thread — the cap bounds that blast radius.
 *
 * Fail-open by design: a missing/malformed ruleset never breaks the agent
 * loop. The parsed ruleset is cached behind a stat gate (mtime+size,
 * re-checked at most once per reloadIntervalMs) so the per-call cost on a
 * hot path is one File.stat.
 */
class HookEngine(
    private val rulesetPath: String,
    private val logger: EngineLogger = EngineLogger.NONE,
    private val maxMatchInput: Int = 2000,
    private val reloadIntervalMs: Long = 5000,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class Phase { PRE, POST }
    enum class Action { BLOCK, WARN }

    class Verdict(val action: Action, val message: String)

    private data class Rule(
        val tool: String?,
        val regex: Regex?,
        val action: Action,
        val phase: Phase,
        val message: String,
    )

    @Volatile private var rules: List<Rule> = emptyList()
    @Volatile private var loadedAtMs: Long = 0
    @Volatile private var statKey: String = ""

    /** Hot path. Returns null when no PRE rule engages (the common case). */
    fun evaluatePre(toolName: String, argsJson: String): Verdict? =
        evaluate(Phase.PRE, toolName, argsJson)

    /** Post-execution feedback. Returns null when no POST rule engages. */
    fun evaluatePost(toolName: String, argsJson: String): Verdict? =
        evaluate(Phase.POST, toolName, argsJson)

    private fun evaluate(phase: Phase, toolName: String, argsJson: String): Verdict? {
        maybeReload()
        if (rules.isEmpty()) return null
        val hay = argsJson.take(maxMatchInput).lowercase()
        for (r in rules) {
            if (r.phase != phase) continue
            if (r.tool != null && r.tool != toolName) continue
            if (r.regex != null && !r.regex.containsMatchIn(hay)) continue
            return Verdict(r.action, r.message)
        }
        return null
    }

    private fun maybeReload() {
        val now = clock()
        if (now - loadedAtMs <= reloadIntervalMs) return
        loadedAtMs = now
        val f = File(rulesetPath)
        val key = if (f.exists()) "${f.lastModified()}:${f.length()}" else "absent"
        if (key == statKey) return
        statKey = key
        reload(f)
    }

    private fun reload(f: File) {
        rules = runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("rules") ?: JSONArray()
            val out = ArrayList<Rule>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val tool = o.optString("tool", "").trim().lowercase().ifEmpty { null }
                val pattern = o.optString("match", "").trim()
                val regex = if (pattern.isEmpty()) null else runCatching {
                    Regex(pattern.lowercase())
                }.getOrNull()
                val phase = when (o.optString("phase", "pre").trim().lowercase()) {
                    "post" -> Phase.POST
                    else -> Phase.PRE
                }
                // A "block" in post phase cannot un-run the call — degrade
                // to feedback so a misphased rule still says its message.
                val wantsBlock = o.optString("action", "").trim().lowercase() == "block"
                val action = if (wantsBlock && phase == Phase.PRE) Action.BLOCK else Action.WARN
                out.add(Rule(
                    tool = tool,
                    regex = regex,
                    action = action,
                    phase = phase,
                    message = o.optString("message", "").ifBlank { "hook engaged" },
                ))
            }
            out
        }.getOrElse {
            logger.log(EngineLogger.Level.WARNING, "HookEngine",
                "ruleset invalid — ignored (fail-open): ${it.message}")
            emptyList()
        }
    }
}
