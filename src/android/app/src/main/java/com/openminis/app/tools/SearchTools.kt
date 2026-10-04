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
                description = "File or directory to search (default: /var/minis).",
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
                description = "Root directory (default: /var/minis).",
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
        return com.openminis.app.sandbox.PRootKernel.resolveSessionHostPath(sessionId, abs, context)
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
}
