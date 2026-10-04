package com.openminis.app.engine

/**
 * [T-engine-taxonomy-table] Static classification of the built-in tool
 * surface. Lives in the engine (not in each tool) so plan mode and the
 * permission gate can classify a tool BEFORE its adapter is even loaded,
 * and so a hypothetical future tool with no entry gets the safe default.
 *
 * Unknown names classify as EXECUTE: in AUTO that is today's behavior, in
 * EDIT it forces a confirmation, in PLAN it is denied unless the specific
 * call provably only reads (see DefaultPermissionGate).
 */
object ToolTaxonomy {

    fun kindOf(toolName: String): MutationKind = when (toolName) {
        "file_read", "read_image", "memory_get",
        "todo_read", "bg_check", "bg_list" -> MutationKind.READ

        "web_search", "webfetch", "browser_use" -> MutationKind.NETWORK

        "file_write", "file_edit", "memory_write" -> MutationKind.WRITE

        "todo_write", "ask_user", "subagent_task", "bg_steer",
        "turn_timer" -> MutationKind.META

        // shell_execute, bg_run, bg_kill, mcp, anything unknown
        else -> MutationKind.EXECUTE
    }
}
