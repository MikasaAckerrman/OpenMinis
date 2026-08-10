package com.openminis.app.tools

import java.util.concurrent.ConcurrentHashMap

/**
 * [A3] Maps a worker session back to the graph node it is running.
 *
 * Why this is needed: tools execute inside ChatViewModel, which knows only its
 * own `sessionId`. The live progress card is keyed by `taskId` + `runtimeId`, so
 * without this lookup the executor cannot say which row to update — and "which
 * tool is this agent running" was the one piece of information a minutes-long
 * node could not report.
 *
 * In-memory on purpose, same reasoning as AgentToolPolicyStore and
 * AgentRunProgress itself: this describes a run happening NOW, and it is
 * worthless a minute after the run ends. The durable record is the trace file.
 */
object AgentNodeBinding {

    data class Binding(val taskId: String, val runtimeId: String)

    private val bindings = ConcurrentHashMap<String, Binding>()

    fun bind(sessionId: String, taskId: String, runtimeId: String) {
        if (sessionId.isBlank()) return
        bindings[sessionId] = Binding(taskId, runtimeId)
    }

    fun of(sessionId: String): Binding? = bindings[sessionId]

    fun unbind(sessionId: String) {
        bindings.remove(sessionId)
    }

    fun clearAll() {
        bindings.clear()
    }
}
