package com.openminis.app.offload

import com.openminis.app.data.model.AgentNode

/**
 * Enforces per-agent tool allowlists.
 * Tool names match the minis CLI/tools available:
 * - shell, file_read, file_write, file_edit, browser
 * - sessions, model_use, config, memory, android_*
 */
object ToolAllowlistEnforcer {

    /** All known tool names in the system. */
    val ALL_TOOLS = setOf(
        "shell", "shell_execute",
        "file_read", "file_write", "file_edit",
        "browser", "browser_use",
        "sessions", "minis-sessions-cli",
        "model_use", "minis-model-use",
        "config", "minis-config",
        "memory", "memory_write", "memory_get",
        "android_alarm", "android_calendar", "android_clipboard",
        "android_contacts", "android_device", "android_location",
        "android_notification", "android_open", "android_photos",
        "android_player", "android_speak", "android_speech",
        "android_weather", "android_shizuku_cli", "android_a11y_cli",
    )

    /** Check if a tool is allowed for a given node. */
    fun isAllowed(node: AgentNode, tool: String): Boolean {
        if (node.allowedTools.isEmpty()) return true // empty = all allowed (backward compat)
        val normalized = normalizeToolName(tool)
        return node.allowedTools.any { normalizeToolName(it) == normalized }
    }

    /** Filter a response to remove disallowed tool calls. */
    fun filterResponse(response: String, allowedTools: List<String>): String {
        if (allowedTools.isEmpty()) return response
        // This would parse tool calls from the response and filter them
        // For now, we rely on the model respecting the system prompt
        return response
    }

    /** Normalize tool name variants. */
    private fun normalizeToolName(name: String): String {
        return name.lowercase()
            .replace("_", "-")
            .replace("minis-", "")
            .replace("android-", "")
            .replace("cli", "")
    }

    /** Get the allowlist as a formatted string for system prompts. */
    fun formatAllowlist(node: AgentNode): String {
        if (node.allowedTools.isEmpty()) return "All tools available"
        return "Allowed tools: ${node.allowedTools.joinToString(", ")}"
    }
}