package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.engine.TodoItem
import com.openminis.app.engine.TodoStatus
import com.openminis.app.engine.TodoStore
import org.json.JSONArray
import org.json.JSONObject

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
 * Storage lives in the engine ([TodoStore]): process-memory per session —
 * todos are working state, they survive session switches in the app
 * process and intentionally don't outlive it (the agent re-derives the
 * plan from its memory notes after a restart). This object is the thin
 * tool-surface adapter: argument parsing + result shaping only.
 */
object TodoTool {

    const val WRITE_NAME = "todo_write"
    const val READ_NAME = "todo_read"

    private val store = TodoStore.shared

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
        if (items.size > store.maxItems) {
            return ToolExecutionResult("Error: max ${store.maxItems} todo items", false)
        }
        val stored = store.write(sessionId, items)
        return ToolExecutionResult(TodoStore.render(stored) + "\n(list saved for this session)", true)
    }

    fun read(sessionId: String): ToolExecutionResult {
        val items = store.read(sessionId)
        if (items.isEmpty()) {
            return ToolExecutionResult("no todos for this session", true)
        }
        return ToolExecutionResult(TodoStore.render(items), true)
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
                                out.add(TodoItem(content, TodoStatus.fromString(item.optString("status", "pending"))))
                            }
                        }
                        is String -> if (item.isNotBlank()) out.add(TodoItem(item.trim(), TodoStatus.PENDING))
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
                return parts.ifEmpty { null }?.map { TodoItem(it, TodoStatus.PENDING) }
            }
        }
        return null
    }
}
