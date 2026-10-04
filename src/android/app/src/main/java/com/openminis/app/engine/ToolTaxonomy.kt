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
        "todo_read", "bg_check", "bg_list",
        "memory_blocks_view", "supermemory_search",
        "grep", "glob", "list_agents", "task_board" -> MutationKind.READ

        "web_search", "webfetch", "browser_use" -> MutationKind.NETWORK

        "file_write", "file_edit", "memory_write",
        "memory_blocks_edit" -> MutationKind.WRITE

        // plan_submit is the PLAN-mode EXIT — it only ever asks the user,
        // so it must run in every mode or the mode could never end.
        // ("subagent_task" is the iOS-side name for delegation; the Android
        // spawn_* / run_graph names classify as EXECUTE via the default.)
        "todo_write", "ask_user", "plan_submit", "subagent_task", "bg_steer",
        "turn_timer" -> MutationKind.META

        // shell_execute, bg_run, bg_kill, mcp, root_shell, session_gc,
        // spawn_subagent, spawn_many, run_graph, anything unknown —
        // delegation classifies conservatively: children of a PLAN session
        // must not be spawned to write around the mode.
        else -> MutationKind.EXECUTE
    }
}
