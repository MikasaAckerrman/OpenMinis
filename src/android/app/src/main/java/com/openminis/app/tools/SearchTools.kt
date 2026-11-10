package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * [T-embedded-search] grep + glob agent tools over the bundled native
 * ripgrep (ZCode port). The point is not only speed — it is the PATH
 * contract: the model passes guest-style paths (/var/minis/...), the
 * tools resolve them to HOST bind targets and scan those directly,
 * bypassing the PRoot shell completely.
 *
 * Relationship to shell_execute: these tools replace the
 * `grep -r ... | head` pattern — one structured call, no shell spawn,
 * no quoting, bounded output, files-only traversal (no /proc noise).
 */
object SearchTools {

    const val GREP_NAME = "grep"
    const val GLOB_NAME = "glob"

    private const val DEFAULT_MAX_RESULTS = 200

    fun definitions(): List<AgentToolDefinition> = listOf(grepDefinition(), globDefinition())

    private fun grepDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = GREP_NAME,
        description = "Fast native content search (bundled ripgrep, NO shell spawn). " +
            "Use INSTEAD of shell_execute grep/grep -r: faster, bounded output, no " +
            "quoting pitfalls. Searches the Linux filesystem (guest paths like " +
            "/var/minis/workspace). Regex syntax: PCRE-lite (ripgrep default — no " +
            "lookarounds unless you pass pcre=true).",
        parameters = mapOf(
            "pattern" to AgentToolParam(
                type = "string",
                description = "Regex pattern (case-insensitive by default).",
            ),
            "path" to AgentToolParam(
                type = "string",
                description = "File or directory to search (default: this session's /var/minis/workspace).",
            ),
            "glob" to AgentToolParam(
                type = "string",
                description = "Filter files by glob, e.g. \"*.kt\" or \"*.{kt,java}\".",
            ),
            "context" to AgentToolParam(
                type = "integer",
                description = "Context lines around each match (default 0).",
            ),
            "max_results" to AgentToolParam(
                type = "integer",
                description = "Max matched LINES returned (default 200).",
            ),
            "files_with_matches" to AgentToolParam(
                type = "boolean",
                description = "true = return only file paths (-l), not lines.",
            ),
            "case_sensitive" to AgentToolParam(
                type = "boolean",
                description = "false (default) = insensitive.",
            ),
            "pcre" to AgentToolParam(
                type = "boolean",
                description = "true = full PCRE2 (lookarounds/backrefs).",
            ),
        ),
        required = listOf("pattern"),
    )

    private fun globDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = GLOB_NAME,
        description = "Fast native file listing by glob pattern (bundled ripgrep " +
            "--files --glob, NO shell spawn). Use instead of shell_execute find/ls " +
            "when you need files by name pattern. Returns paths sorted.",
        parameters = mapOf(
            "pattern" to AgentToolParam(
                type = "string",
                description = "Glob, e.g. \"*.kt\", \"**/test*.py\", \"*.json\".",
            ),
            "path" to AgentToolParam(
                type = "string",
                description = "Root directory (default: this session's /var/minis/workspace).",
            ),
            "max_results" to AgentToolParam(
                type = "integer",
                description = "Max paths returned (default 200).",
            ),
        ),
        required = listOf("pattern"),
    )

    suspend fun executeGrep(
        argsJson: String,
        sessionId: String,
        context: android.content.Context,
    ): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val args = runCatching { JSONObject(argsJson) }.getOrElse {
                return@withContext ToolExecutionResult("Error: malformed arguments", false)
            }
            val pattern = args.optString("pattern", "")
            if (pattern.isEmpty()) {
                return@withContext ToolExecutionResult("Error: 'pattern' is required", false)
            }
            val guestPath = args.optString("path", "/var/minis").trim()
            val maxResults = args.optInt("max_results", DEFAULT_MAX_RESULTS).coerceIn(1, 2000)

            val host = resolveHost(sessionId, guestPath, context)
                ?: return@withContext ToolExecutionResult(
                    "Error: cannot resolve path: $guestPath", false)
            // [T-embedded-search-default] A missing host target must fail
            // with a model-actionable message BEFORE rg turns it into an
            // unexplained exit 2.
            if (!host.exists()) {
                return@withContext ToolExecutionResult(
                    "Error: path not present in this session's sandbox: $guestPath " +
                        "(search inside /var/minis/workspace)", false)
            }

            // [M-R3.3] Daemon path first: the rust daemon greps the session
            // workspace in-memory — no fork, no pipes (the B45 deadlock
            // class). The output is re-rendered into the rg shape, so the
            // model sees ONE format whichever path answered. Anything the
            // daemon cannot serve (not eligible, daemon down, wrong
            // workspace, business error) falls through to the local fork.
            if (grepRpcEligible(args)) {
                val rel = workspaceRelative(guestPath)
                if (rel != null &&
                    com.openminis.app.sandbox.TelegramVaultManager
                        .ensureWorkspaceFor(sessionId, context)
                ) {
                    val filesOnly = args.optBoolean("files_with_matches", false)
                    val rpcArgs = org.json.JSONObject()
                        .put("pattern", pattern)
                        .put("ignore_case", !args.optBoolean("case_sensitive", false))
                    if (rel.isNotEmpty()) rpcArgs.put("path", rel)
                    val envelope = com.openminis.app.sandbox.MinisdToolClient
                        .toolsCall(context, GREP_NAME, rpcArgs)
                    if (envelope != null) {
                        return@withContext ToolExecutionResult(
                            formatGrepResult(envelope, maxResults, filesOnly), true)
                    }
                }
            }

            val argv = mutableListOf<String>()
            if (!args.optBoolean("case_sensitive", false)) argv.add("-i")
            if (args.optBoolean("files_with_matches", false)) argv.add("-l")
            args.optInt("context", 0).let { if (it > 0) argv += listOf("-C", it.toString()) }
            argv += listOf("-m", maxResults.toString())
            if (args.optBoolean("pcre", false)) argv.add("-P")
            args.optString("glob", "").takeIf { it.isNotBlank() }?.let {
                argv += listOf("--glob", it)
            }
            argv += listOf("--", pattern, host.absolutePath)

            val (out, code) = com.openminis.app.sandbox.NativeSearch.exec(
                context, argv)
            finishSearch(out, code, "grep")
        }

    suspend fun executeGlob(
        argsJson: String,
        sessionId: String,
        context: android.content.Context,
    ): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val args = runCatching { JSONObject(argsJson) }.getOrElse {
                return@withContext ToolExecutionResult("Error: malformed arguments", false)
            }
            val pattern = args.optString("pattern", "")
            if (pattern.isEmpty()) {
                return@withContext ToolExecutionResult("Error: 'pattern' is required", false)
            }
            val guestPath = args.optString("path", "/var/minis").trim()
            val maxResults = args.optInt("max_results", DEFAULT_MAX_RESULTS).coerceIn(1, 2000)
            val host = resolveHost(sessionId, guestPath, context)
                ?: return@withContext ToolExecutionResult(
                    "Error: cannot resolve path: $guestPath", false)
            if (!host.exists()) {
                return@withContext ToolExecutionResult(
                    "Error: path not present in this session's sandbox: $guestPath " +
                        "(search inside /var/minis/workspace)", false)
            }

            // [M-R3.3] Daemon path first — same contract as grep's route
            // above (eligibility gate, workspace-relative translation,
            // rg-shaped output, local fork fallback).
            if (globRpcEligible(args)) {
                val rel = workspaceRelative(guestPath)
                if (rel != null &&
                    com.openminis.app.sandbox.TelegramVaultManager
                        .ensureWorkspaceFor(sessionId, context)
                ) {
                    val rpcArgs = org.json.JSONObject().put("pattern", pattern)
                    if (rel.isNotEmpty()) rpcArgs.put("path", rel)
                    val envelope = com.openminis.app.sandbox.MinisdToolClient
                        .toolsCall(context, GLOB_NAME, rpcArgs)
                    if (envelope != null) {
                        return@withContext ToolExecutionResult(
                            formatGlobResult(envelope, maxResults), true)
                    }
                }
            }

            val argv = mutableListOf<String>()
            argv += listOf("--files", "--glob", pattern)
            argv += listOf("-m", maxResults.toString())
            argv += listOf("--", host.absolutePath)

            val (out, code) = com.openminis.app.sandbox.NativeSearch.exec(
                context, argv)
            finishSearch(out, code, "glob")
        }

    private fun resolveHost(
        sessionId: String,
        guestPath: String,
        context: android.content.Context,
    ): File? {
        val abs = if (guestPath.startsWith("/")) guestPath else "/var/minis/$guestPath"
        // [T-embedded-search-default] The bare /var/minis has NO host
        // counterpart (the workspace is a per-session bind; the rest rides
        // minis-global) — resolveHostPath("/var/minis") hands rg a host
        // path that does not exist and rg dies exit 2 (vc155 live, 08.11).
        // The honest default is THIS session's workspace: where the
        // model's own files actually live.
        if (abs == "/var/minis" || abs == "/var/minis/") {
            return com.openminis.app.sandbox.PRootKernel.resolveSessionHostPath(
                sessionId, "/var/minis/workspace", context)
        }
        return com.openminis.app.sandbox.PRootKernel.resolveSessionHostPath(
            sessionId, abs, context)
    }

    private fun finishSearch(out: String, code: Int, tool: String): ToolExecutionResult {
        val body = out.trim()
        return when {
            code == 0 -> ToolExecutionResult(if (body.isEmpty()) "(no matches)" else body, true)
            code == 1 -> ToolExecutionResult("(no matches)", true)
            code == 124 -> ToolExecutionResult(body, false)
            else -> ToolExecutionResult("rg error (exit $code):\n$body", false)
        }
    }

    // ── [M-R3.3] RPC routing helpers ────────────────────────────────────
    // Pure, unit-pinned decision + rendering logic for the daemon path.

    /**
     * True when the args shape maps 1:1 onto the daemon's grep contract:
     * the daemon search is a LITERAL substring scan with a fixed 200-hit
     * cap — regex syntax, context lines and the glob pre-filter have no
     * daemon equivalent, and routing them would silently change results.
     * `case_sensitive` and `files_with_matches` map cleanly (ignore_case
     * inversion / file dedupe in the formatter).
     */
    internal fun grepRpcEligible(args: JSONObject): Boolean {
        if (args.optInt("context", 0) > 0) return false
        if (args.optBoolean("pcre", false)) return false
        if (!args.optString("glob", "").isBlank()) return false
        return isLiteralPattern(args.optString("pattern", ""))
    }

    /**
     * Same gate for glob: the daemon matcher implements the star, question
     * and double-star forms but NOT braces (`*.{kt,java}`) or bracket
     * classes — those patterns stay on rg, which does expand them.
     */
    internal fun globRpcEligible(args: JSONObject): Boolean {
        val pattern = args.optString("pattern", "")
        if (pattern.isEmpty()) return false
        return !pattern.any { it in "{}[]" }
    }

    /** A regex metachar in the pattern means rg semantics, not substring. */
    internal fun isLiteralPattern(pattern: String): Boolean =
        pattern.none { it in "\\^$.|?*+()[]{}" }

    /**
     * Guest path → daemon workspace-relative path. The daemon resolves
     * relative args against ITS ONE --workspace root, which IS the session
     * workspace — so only guest paths inside /var/minis/workspace (plus
     * the bare /var/minis default, which [resolveHost] already maps to the
     * workspace) are servable; everything else keeps the local rg path
     * with the exact pre-M-R3.3 semantics.
     */
    internal fun workspaceRelative(guestPath: String): String? {
        val abs = (if (guestPath.startsWith("/")) guestPath else "/var/minis/$guestPath")
            .trimEnd('/')
        if (abs == "/var/minis" || abs.isEmpty() || abs == "/var/minis/workspace") return ""
        if (abs.startsWith("/var/minis/workspace/")) {
            return abs.removePrefix("/var/minis/workspace/")
        }
        return null
    }

    /**
     * Daemon grep envelope → the rg-shaped text the model has always
     * seen: `file:line:text` lines (paths are workspace-relative, exactly
     * what a follow-up file_read wants). `files_with_matches` dedupes to
     * the `-l` shape. The daemon's 200-hit cap surfaces as an explicit
     * truncation note instead of a silent cut.
     */
    internal fun formatGrepResult(envelope: JSONObject, maxResults: Int, filesOnly: Boolean): String {
        val matches = envelope.optJSONArray("matches") ?: return "(no matches)"
        if (matches.length() == 0) return "(no matches)"
        val truncated = envelope.optBoolean("truncated", false)
        if (filesOnly) {
            val files = LinkedHashSet<String>()
            for (i in 0 until matches.length()) {
                matches.optJSONObject(i)?.let { files.add(it.optString("file")) }
            }
            val head = files.take(maxResults).joinToString("\n")
            return if (truncated) {
                "$head\n(more files contain matches; refine the pattern)"
            } else head
        }
        val lines = ArrayList<String>(matches.length())
        for (i in 0 until matches.length()) {
            val m = matches.optJSONObject(i) ?: continue
            lines.add("${m.optString("file")}:${m.optInt("line")}:${m.optString("text")}")
        }
        val head = lines.take(maxResults).joinToString("\n")
        return if (truncated) {
            "$head\n(more matches exist; refine the pattern or narrow the path)"
        } else head
    }

    /** Daemon glob envelope → newline-joined workspace-relative paths. */
    internal fun formatGlobResult(envelope: JSONObject, maxResults: Int): String {
        val matches = envelope.optJSONArray("matches") ?: return "(no matches)"
        if (matches.length() == 0) return "(no matches)"
        val paths = (0 until matches.length()).mapNotNull { matches.optString(it) }
        val head = paths.take(maxResults).joinToString("\n")
        return if (envelope.optBoolean("truncated", false)) {
            "$head\n(more paths match; refine the pattern)"
        } else head
    }
}
