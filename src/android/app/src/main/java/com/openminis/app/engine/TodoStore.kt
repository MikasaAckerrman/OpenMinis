package com.openminis.app.engine

/**
 * [T-todo-store] Per-session working checklist, engine-side.
 *
 * The store is intentionally process-memory (ConcurrentHashMap): todos are
 * working state — they survive session switches in the app process and
 * intentionally don't outlive it; the agent re-derives the plan from its
 * memory notes after a restart.
 *
 * Write semantics: a write REPLACES the session's whole list (send the
 * complete state, not a delta) — idempotent, no merge logic, no drift
 * between what the model believes and what the user sees.
 */
enum class TodoStatus {
    PENDING, IN_PROGRESS, DONE;

    companion object {
        /** Lenient normalization — providers emit creative status strings. */
        fun fromString(raw: String): TodoStatus = when (raw.trim().lowercase()) {
            "in_progress", "active", "doing" -> IN_PROGRESS
            "done", "complete", "completed" -> DONE
            else -> PENDING
        }
    }
}

data class TodoItem(val content: String, val status: TodoStatus)

class TodoStore(val maxItems: Int = 50) {

    private val bySession = java.util.concurrent.ConcurrentHashMap<String, List<TodoItem>>()

    /**
     * Replace the session's list. Over-long input is truncated to
     * [maxItems] and the stored list is returned (adapters that want a hard
     * error on overflow check size before calling). An empty list clears.
     */
    fun write(sessionId: String, items: List<TodoItem>): List<TodoItem> {
        val bounded = items.take(maxItems)
        if (bounded.isEmpty()) bySession.remove(sessionId) else bySession[sessionId] = bounded
        return bounded
    }

    fun read(sessionId: String): List<TodoItem> = bySession[sessionId] ?: emptyList()

    fun clear(sessionId: String) {
        bySession.remove(sessionId)
    }

    companion object {
        /**
         * Process-wide default instance. Adapters (tools/TodoTool) hang off
         * this until the tool-dispatch migration (M6) injects the store
         * through ToolContext.
         */
        val shared: TodoStore by lazy { TodoStore() }

        /** The user-visible rendering — ✓ done, ▸ active, ○ pending. */
        fun render(items: List<TodoItem>): String {
            val done = items.count { it.status == TodoStatus.DONE }
            return buildString {
                append("TODO [").append(done).append('/').append(items.size).append("]\n")
                items.forEach { item ->
                    val marker = when (item.status) {
                        TodoStatus.DONE -> "✓"
                        TodoStatus.IN_PROGRESS -> "▸"
                        TodoStatus.PENDING -> "○"
                    }
                    append(marker).append(' ').append(item.content).append('\n')
                }
            }.trim()
        }
    }
}
