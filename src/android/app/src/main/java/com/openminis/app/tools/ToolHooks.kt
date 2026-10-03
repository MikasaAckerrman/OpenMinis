package com.openminis.app.tools

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [T-tool-hooks] PreToolUse hooks — user-side extensibility without
 * forking the app (the ZCode "hooks" gap). A JSON ruleset at
 * /var/minis/.config/hooks.json intercepts tool calls BEFORE execution:
 *
 * {
 *   "rules": [
 *     {"tool": "shell_execute", "match": "rm -rf", "action": "block",
 *      "message": "no rm -rf in this house"},
 *     {"tool": "bg_run", "match": "wget|curl", "action": "warn",
 *      "message": "background network access"}
 *   ]
 * }
 *
 * Actions: "block" (the call is refused with the message), "warn" (the
 * call runs, the warning is prepended to its result), anything else =
 * inert documentation. Matching: "match" is a case-insensitive substring
 * regex against the RAW tool-arguments JSON — deliberately raw, so a rule
 * can key on any field the model sends; agents review their own rules
 * with file_read on the same path.
 *
 * Fail-open by design: a broken/missing ruleset never breaks the agent
 * loop — hooks are policy, not infrastructure. Cached with a stat-gate
 * (mtime+size) so the per-call cost is one File.stat on a hot path.
 */
object ToolHooks {

    private const val HOOKS_PATH = "/var/minis/.config/hooks.json"
    private const val TAG = "ToolHooks"

    class Verdict(val blocked: Boolean, val warning: String?)

    private data class Rule(
        val tool: String?,
        val regex: Regex?,
        val block: Boolean,
        val message: String,
    )

    // Stat-cached ruleset (same discipline as SoulStore/SkillRepository).
    @Volatile private var rules: List<Rule> = emptyList()
    @Volatile private var loadedAtMs: Long = 0
    @Volatile private var statKey: String = ""

    fun hooksPath(): String = HOOKS_PATH

    /** Hot-path entry: returns null when no rule engages (the common case). */
    fun evaluate(toolName: String, argsJson: String): Verdict? {
        val now = System.currentTimeMillis()
        if (now - loadedAtMs > 5000) {
            val f = File(HOOKS_PATH)
            val key = if (f.exists()) "${f.lastModified()}:${f.length()}" else "absent"
            if (key != statKey) reload(f)
            statKey = key
            loadedAtMs = now
        }
        if (rules.isEmpty()) return null
        val hay = argsJson.lowercase()
        for (r in rules) {
            if (r.tool != null && r.tool != toolName) continue
            if (r.regex != null && !r.regex.containsMatchIn(hay)) continue
            return Verdict(r.block, if (r.block) null else r.message)
        }
        return null
    }

    private fun reload(f: File) {
        val parsed = runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("rules") ?: JSONArray()
            val out = ArrayList<Rule>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val tool = o.optString("tool", "").trim().lowercase().ifEmpty { null }
                val pattern = o.optString("match", "").trim()
                val regex = if (pattern.isEmpty()) null else runCatching {
                    Regex(pattern.lowercase())
                }.getOrNull()
                val action = o.optString("action", "").trim().lowercase()
                out.add(Rule(
                    tool = tool,
                    regex = regex,
                    block = action == "block",
                    message = o.optString("message", "").ifBlank { "hook engaged" },
                ))
            }
            out
        }.getOrElse {
            Log.w(TAG, "hooks.json invalid — ignored (fail-open): ${it.message}")
            emptyList()
        }
        rules = parsed
    }
}
