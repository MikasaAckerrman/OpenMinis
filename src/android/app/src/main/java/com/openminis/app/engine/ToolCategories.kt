package com.openminis.app.engine

/**
 * [B16] Functional categories of the agent tool surface — the UI-facing
 * grouping that [ToolTaxonomy] deliberately does not provide (taxonomy =
 * PERMISSION axis READ/NETWORK/WRITE/META/EXECUTE, categories = DOMAIN axis
 * File/Shell/Web/Memory/Subagent/System, per the synced B16 spec). Two
 * different questions: "may this call mutate?" vs "what is this call for?".
 *
 * This is the SINGLE source of the canonical allowlist-able tool set:
 *  - [AgentTools.ALL_TOOL_NAMES] derives from [ALL_TOOLS] (the allowlist
 *    enforcer and the schema filter validate against it);
 *  - [com.openminis.app.offload.AgentFileParser] validates `tools:` lines
 *    against it (the old 8-name KNOWN_TOOL_NAMES silently dropped 26 real
 *    tools — the B16 bottleneck);
 *  - the Custom Agents screen renders the picker from [CATEGORIES].
 *
 * Every name here is BOTH dispatchable (a branch in ChatViewModel's tool
 * switch) AND schema-backed (registered in ToolSurfaceAdapter.buildRegistry
 * under its feature gate — subagents/memory/core-memory/root-shell). A tool
 * that is off at runtime simply never registers, so allowlisting it is
 * harmless (empty intersection with the schema, no broken promise — the
 * model never sees it).
 *
 * Deliberately NOT here:
 *  - `plan_submit` — the PLAN-mode exit. It is injected engine-side only
 *    while plan mode is armed; a worker session never has plan mode, so
 *    allowlisting it would promise a tool the schema can never contain.
 *  - `subagent_task` — the iOS-side delegation name (see ToolTaxonomy); the
 *    Android dispatcher exposes spawn_subagent/spawn_many/run_graph instead.
 */
object ToolCategories {

    /** A functional group: stable [key], UI [title], canonical tool [tools]. */
    data class ToolCategory(
        val key: String,
        val title: String,
        val tools: List<String>,
    )

    /** Filesystem surface: reads, writes, edits and the native search pair. */
    val FILE = ToolCategory(
        key = "file",
        title = "File",
        tools = listOf(
            "file_read", "file_write", "file_edit", "read_image",
            "grep", "glob",
        ),
    )

    /**
     * Shell surface incl. the background family (bg_* are shells detached
     * from the turn — they belong with their synchronous sibling, not with
     * "System") and the master-gated root shell.
     */
    val SHELL = ToolCategory(
        key = "shell",
        title = "Shell",
        tools = listOf(
            "shell_execute", "root_shell",
            "bg_run", "bg_check", "bg_list", "bg_kill", "bg_steer",
        ),
    )

    /** Network surface: search, the light single-page fetch, the browser. */
    val WEB = ToolCategory(
        key = "web",
        title = "Web",
        tools = listOf("web_search", "webfetch", "browser_use"),
    )

    /** Memory surface: daily log, semantic tier and the Letta-style blocks. */
    val MEMORY = ToolCategory(
        key = "memory",
        title = "Memory",
        tools = listOf(
            "memory_write", "memory_get", "supermemory_search",
            "memory_blocks_view", "memory_blocks_edit",
        ),
    )

    /** Delegation surface: spawning, orchestration and team observation. */
    val SUBAGENT = ToolCategory(
        key = "subagent",
        title = "Subagent",
        tools = listOf(
            "spawn_subagent", "spawn_many", "cancel_subagent", "await_result",
            "run_graph", "list_agents", "task_board",
        ),
    )

    /** Session machinery: checklists, user interaction, timers, MCP, GC. */
    val SYSTEM = ToolCategory(
        key = "system",
        title = "System",
        tools = listOf(
            "todo_write", "todo_read", "ask_user", "turn_timer",
            "mcp", "session_gc",
        ),
    )

    /** Display order — File first (the most common allowlists start there). */
    val CATEGORIES: List<ToolCategory> = listOf(FILE, SHELL, WEB, MEMORY, SUBAGENT, SYSTEM)

    /** The full canonical set: the union, materialised once. */
    val ALL_TOOLS: Set<String> = CATEGORIES.flatMapTo(LinkedHashSet()) { it.tools }

    /** The category a canonical tool belongs to; null = not a known tool. */
    fun categoryOf(toolName: String): ToolCategory? =
        CATEGORIES.firstOrNull { toolName in it.tools }
}
