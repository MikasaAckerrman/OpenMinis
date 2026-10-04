package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * [T-mcp-first-class] MCP as a first-class agent tool — closes the
 * "discovery and calls go through raw shell" gap from the ZCode
 * comparison without exploding the tool schema: ONE structured tool
 * instead of `minis-mcp-cli` invocations via shell_execute.
 *
 * What this buys over the shell path:
 * - no shell-quoting of JSON arguments (the #1 source of mangled calls);
 * - server allowlist from servers.json (disabled servers are refused);
 * - output capped and JSON-structured for the model, not raw terminal;
 * - the schema carries the usage pattern, so no prompt-space is spent.
 *
 * Workflow for the model: `mcp(action=list)` once per conversation →
 * server names; `mcp(action=tools, server=…)` when needed → tool names
 * and arg hints; `mcp(action=call, server=…, tool=…, args={…})` to run.
 *
 * Execution goes through the in-guest `minis-mcp-cli` (same daemon the
 * manual shell path uses — one subprocess per call, stdin-free, no
 * shared state) via ExecutionCoordinator.
 */
object McpCallTool {

    const val NAME = "mcp"
    private const val MAX_OUTPUT_CHARS = 8000
    private const val DEFAULT_TIMEOUT_SEC = 60

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Call MCP (Model Context Protocol) servers — FIRST-CLASS: do NOT " +
            "use shell_execute with minis-mcp-cli, use this tool. Workflow: " +
            "1) mcp(action=\"list\") → configured servers; " +
            "2) mcp(action=\"tools\", server=\"…\") → that server's tools with argument " +
            "hints; 3) mcp(action=\"call\", server=\"…\", tool=\"…\", args=\"{…}\") → result. " +
            "args is a JSON object string. Use it for gmail/github/telegram/search " +
            "integrations — one call, structured output.",
        parameters = mapOf(
            "action" to AgentToolParam(
                type = "string",
                description = "list | tools | call",
                enumValues = listOf("list", "tools", "call"),
            ),
            "server" to AgentToolParam(
                type = "string",
                description = "Server name (from action=list / the settings).",
            ),
            "tool" to AgentToolParam(
                type = "string",
                description = "Tool name on that server (from action=tools).",
            ),
            "args" to AgentToolParam(
                type = "string",
                description = "JSON object string with the tool's input, e.g. \"{\\\"q\\\":\\\"…\\\"}\".",
            ),
            "timeout" to AgentToolParam(
                type = "integer",
                description = "Seconds for the call (default 60, max 300).",
            ),
        ),
        required = listOf("action"),
    )

    suspend fun execute(
        argsJson: String,
        sessionId: String,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return@withContext ToolExecutionResult("Error: malformed arguments", false)
        }
        val action = args.optString("action", "").trim().lowercase()
        val server = args.optString("server", "").trim()
        val tool = args.optString("tool", "").trim()
        val toolArgs = args.optString("args", "").trim()
        val timeoutSec = args.optInt("timeout", DEFAULT_TIMEOUT_SEC).coerceIn(5, 300)

        when (action) {
            "list" -> runCli(sessionId, listOf("list"), timeoutSec)
            "tools" -> {
                if (server.isEmpty()) {
                    return@withContext ToolExecutionResult(
                        "Error: 'server' is required for action=tools", false)
                }
                runCli(sessionId, listOf("tools", server), timeoutSec)
            }
            "call" -> {
                if (server.isEmpty() || tool.isEmpty()) {
                    return@withContext ToolExecutionResult(
                        "Error: 'server' and 'tool' are required for action=call", false)
                }
                val argv = mutableListOf("call", server, tool)
                if (toolArgs.isNotEmpty()) {
                    // Validate once so the model gets a readable error
                    // instead of a CLI parse failure buried in stderr.
                    runCatching { JSONObject(toolArgs) }.getOrElse {
                        return@withContext ToolExecutionResult(
                            "Error: 'args' must be a JSON object string — got: ${toolArgs.take(120)}",
                            false)
                    }
                    argv.add("--input")
                    argv.add(toolArgs)
                }
                runCli(sessionId, argv, timeoutSec)
            }
            else -> ToolExecutionResult(
                "Error: unknown action '$action' — use list | tools | call", false)
        }
    }

    /**
     * One guest invocation of minis-mcp-cli. Output is capped for the model
     * context; the command is assembled from validated parts only — never
     * string-concatenated from raw model input (no injection surface).
     */
    private suspend fun runCli(
        sessionId: String,
        argv: List<String>,
        timeoutSec: Int,
    ): ToolExecutionResult {
        // Server allowlist: disabled servers are refused before the guest
        // roundtrip. servers.json path mirrors MCPRepository's host dir.
        val serversJson = File("/var/minis/mcp-servers/servers.json")
        if (serversJson.exists() && argv.size >= 2 && argv[0] != "list") {
            val server = argv[1]
            runCatching {
                val root = JSONObject(serversJson.readText())
                val arr = root.optJSONArray("servers") ?: root.optJSONArray("mcpServers")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optString("name", o.optString("id", "")) == server &&
                            o.optBoolean("enabled", true) == false
                        ) {
                            return ToolExecutionResult(
                                "Server '$server' is disabled in the MCP settings", false)
                        }
                    }
                }
            }
        }
        // --input payload rides shell-quoted by shellQuote (the injection
        // defense: tokens come from validated parts only — the JSON passed
        // a JSONObject check, names are single tokens).
        val quoted = argv.joinToString(" ") { shellQuote(it) }
        val result = com.openminis.app.sandbox.ExecutionCoordinator.execute(
            sessionId = sessionId,
            command = "minis-mcp-cli $quoted",
            timeout = timeoutSec * 1000L,
        )
        val output = result.output.trim()
        val capped = if (output.length > MAX_OUTPUT_CHARS) {
            output.take(MAX_OUTPUT_CHARS) + "\n…(truncated, ${output.length - MAX_OUTPUT_CHARS} chars dropped)"
        } else if (output.isEmpty()) "(no output)" else output
        val ok = result.exitCode == 0
        return ToolExecutionResult(capped, ok)
    }

    /** Minimal single-token shell quote for argv elements we assembled ourselves. */
    private fun shellQuote(token: String): String {
        if (token.isEmpty()) return "''"
        val safe = token.all { it.isLetterOrDigit() || it in ".-_/:=@%+" }
        return if (safe) token else "'" + token.replace("'", "'\\''") + "'"
    }
}
