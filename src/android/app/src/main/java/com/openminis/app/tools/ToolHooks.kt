package com.openminis.app.tools

import android.util.Log
import com.openminis.app.engine.EngineLogger
import com.openminis.app.engine.HookEngine

/**
 * [T-tool-hooks] Tool hooks — user-side extensibility without forking the
 * app. A JSON ruleset at /var/minis/.config/hooks.json intercepts tool
 * calls:
 *
 * {
 *   "rules": [
 *     {"tool": "shell_execute", "match": "rm -rf", "action": "block",
 *      "message": "no rm -rf in this house"},
 *     {"tool": "bg_run", "match": "wget|curl", "action": "warn",
 *      "message": "background network access"},
 *     {"tool": "file_write", "match": "\\.kt$", "action": "warn",
 *      "phase": "post", "message": "remember to run the formatter"}
 *   ]
 * }
 *
 * "pre" (default) runs BEFORE execution: block refuses, warn prepends to
 * the result. "post" runs AFTER execution and feeds the message back to
 * the model (auto-format reminders, audit hints). Matching: case-insensitive
 * regex against the RAW tool-arguments JSON (capped, ReDoS-bounded).
 * Fail-open: a broken/missing ruleset never breaks the agent loop.
 *
 * The policy itself lives in the engine ([HookEngine]); this object is the
 * Android-facing adapter preserving the original evaluate() contract.
 */
object ToolHooks {

    private const val HOOKS_PATH = "/var/minis/.config/hooks.json"
    private const val TAG = "ToolHooks"

    class Verdict(val blocked: Boolean, val warning: String?)

    private val engine = HookEngine(
        rulesetPath = HOOKS_PATH,
        logger = EngineLogger { level, _, message ->
            when (level) {
                EngineLogger.Level.WARNING -> Log.w(TAG, message)
                EngineLogger.Level.ERROR -> Log.e(TAG, message)
                else -> Log.d(TAG, message)
            }
        },
    )

    fun hooksPath(): String = HOOKS_PATH

    /** Hot-path entry: returns null when no PRE rule engages (the common case). */
    fun evaluate(toolName: String, argsJson: String): Verdict? {
        val v = engine.evaluatePre(toolName, argsJson) ?: return null
        // The caller renders blocks as "Blocked by user hook: ${warning ?: ...}"
        // — carry the rule's message in both cases so a block says WHY
        // (the pre-engine implementation dropped it and every block read
        // "no reason given").
        return Verdict(
            blocked = v.action == HookEngine.Action.BLOCK,
            warning = v.message,
        )
    }

    /**
     * Post-execution feedback (the ZCode hook-feedback loop): the message
     * to hand back to the model, or null when no POST rule engages. Wired
     * into the agent loop with M3.
     */
    fun evaluatePost(toolName: String, argsJson: String): String? =
        engine.evaluatePost(toolName, argsJson)?.message
}
