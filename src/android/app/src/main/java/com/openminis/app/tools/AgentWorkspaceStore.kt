package com.openminis.app.tools

import java.util.concurrent.ConcurrentHashMap

/** Host workspace override shared by every worker in one graph run. */
object AgentWorkspaceStore {
    private val hostPaths = ConcurrentHashMap<String, String>()

    fun set(sessionId: String, hostPath: String) {
        if (hostPath.isBlank()) hostPaths.remove(sessionId) else hostPaths[sessionId] = hostPath
    }

    fun get(sessionId: String): String? = hostPaths[sessionId]

    fun clear(sessionId: String) {
        hostPaths.remove(sessionId)
    }

    fun clearAll() {
        hostPaths.clear()
    }
}
