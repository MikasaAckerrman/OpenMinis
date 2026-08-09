package com.openminis.app.tools

import java.util.concurrent.ConcurrentHashMap

/** Runtime-only output-token cap for agent worker sessions. */
object AgentRuntimePolicyStore {
    private val maxOutputTokens = ConcurrentHashMap<String, Int>()

    fun setMaxOutputTokens(sessionId: String, cap: Int) {
        if (cap > 0) maxOutputTokens[sessionId] = cap else maxOutputTokens.remove(sessionId)
    }

    fun maxOutputTokensFor(sessionId: String): Int? = maxOutputTokens[sessionId]

    fun clear(sessionId: String) {
        maxOutputTokens.remove(sessionId)
    }

    fun clearAll() {
        maxOutputTokens.clear()
    }
}
