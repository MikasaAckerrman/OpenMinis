package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-todo] Persistent task checklist — the ZCode "todo/plan" entity: the
 * model keeps a structured list of steps for long work, the user SEES the
 * progress in the tool block (✓/▸/○ rendering, live), and auto-mode
 * continuations can re-read the plan after a context switch.
 *
 * Semantics (Claude Code / ZCode style): todo_write REPLACES the whole
 * list — the model always sends the complete state. Idempotent, no
 * merge logic, no drift between what the model believes and what's shown.
 *
 * Storage is process-memory per session (ConcurrentHashMap): todos are
 * working state — they survive session switches in the app process and
 * intentionally don't outlive it (the agent re-derives the plan from its
 * memory notes after a restart).
 */
object TodoTool {

    const val WRITE_NAME = "todo_write"
    const val READ_NAME = "todo_read"

    data class TodoItem(
        val content: String,
        val status: String, // pending | in_progress | done
    )

    private val bySession = ConcurrentHashMap<String, List<TodoItem>>()

    fun writeDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = WRITE_NAME,
        description = "Create/update the session's task checklist — REPLACES the whole " +
            "list every call (send the complete state, not a delta). Use it whenever a " +
            "task has 3+ steps: it keeps long work on rails, the user sees live " +
            "progress (done/active/pending markers), and continuation turns re-read " +
            "it with todo_read. Mark exactly one item 'in_progress' while working it.",
        parameters = mapOf(
            "todos" to AgentToolParam(
                type = "string",
                description = "Full checklist. JSON array of objects: " +
                    "[{\"content\":\"step text\",\"status\":\"pending|in_progress|done\"}]. " +
                    "Plain strings default to 'pending'.",
            ),
        ),
        required = listOf("todos"),
    )

    fun readDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = READ_NAME,
        description = "Read the session's current checklist (as last written). Returns " +
            "'no todos' when none were set.",
        parameters = emptyMap(),
    )

    fun definitions(): List<AgentToolDefinition> = listOf(writeDefinition(), readDefinition())

    fun write(argsJson: String, sessionId: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return ToolExecutionResult("Error: malformed arguments", false)
        }
        val items = parseItems(args.opt("todos"))
            ?: return ToolExecutionResult(
                "Error: 'todos' is required — JSON array [{content,status}]", false)
        if (items.size > 50) {
            return ToolExecutionResult("Error: max 50 todo items", false)
        }
        bySession[sessionId] = items
        return ToolExecutionResult(render(items) + "\n(list saved for this session)", true)
    }

    fun read(sessionId: String): ToolExecutionResult {
        val items = bySession[sessionId]
            ?: return ToolExecutionResult("no todos for this session", true)
        return ToolExecutionResult(render(items), true)
    }

    /** Lenient parsing: array of objects, array of strings, or 'a;b;c' text. */
    private fun parseItems(raw: Any?): List<TodoItem>? {
        when (raw) {
            is JSONArray -> {
                val out = ArrayList<TodoItem>()
                for (i in 0 until raw.length()) {
                    when (val item = raw.opt(i)) {
                        is JSONObject -> {
                            val content = item.optString("content", item.optString("text", "")).trim()
                            if (content.isNotEmpty()) {
                                val status = when (item.optString("status", "pending").trim().lowercase()) {
                                    "in_progress", "active", "doing" -> "in_progress"
                                    "done", "complete", "completed" -> "done"
                                    else -> "pending"
                                }
                                out.add(TodoItem(content, status))
                            }
                        }
                        is String -> if (item.isNotBlank()) out.add(TodoItem(item.trim(), "pending"))
                    }
                }
                return out.ifEmpty { null }
            }
            is String -> {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return null
                if (trimmed.startsWith("[")) {
                    runCatching { parseItems(JSONArray(trimmed)) }.getOrNull()?.let { return it }
                }
                val parts = trimmed.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
                return parts.ifEmpty { null }?.map { TodoItem(it, "pending") }
            }
        }
        return null
    }

    private fun render(items: List<TodoItem>): String {
        val done = items.count { it.status == "done" }
        val sb = StringBuilder()
        sb.append("TODO [").append(done).append('/').append(items.size).append("]\n")
        items.forEach { item ->
            val marker = when (item.status) {
                "done" -> "✓"
                "in_progress" -> "▸"
                else -> "○"
            }
            sb.append(marker).append(' ').append(item.content).append('\n')
        }
        return sb.toString().trim()
    }
}
