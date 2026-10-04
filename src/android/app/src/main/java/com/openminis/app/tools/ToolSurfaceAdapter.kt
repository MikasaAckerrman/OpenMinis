package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.engine.EngineTool
import com.openminis.app.engine.MutationKind
import com.openminis.app.engine.ToolContext
import com.openminis.app.engine.ToolRegistry
import com.openminis.app.engine.ToolTaxonomy

/**
 * [T-m6-adapter] The M6 thin adapter: one tool of the existing surface as
 * the engine sees it. Carries NO logic — the definition comes from the
 * existing tool objects, the mutation kind from the static taxonomy, and
 * execution is delegated straight back through [ToolContext.dispatch].
 * This is the seam that lets M7 move the loop into the engine without the
 * tools ever noticing.
 */
class EngineToolShell(
    override val name: String,
    private val def: AgentToolDefinition,
) : EngineTool {

    override val mutation: MutationKind = ToolTaxonomy.kindOf(name)

    override fun definition(): AgentToolDefinition = def

    override suspend fun execute(
        argsJson: String,
        ctx: ToolContext,
    ): ToolExecutionResult = ctx.dispatch(name, argsJson)
}

/**
 * [T-m6-registry] The engine registry built from the SAME feature gates as
 * [AgentTools.makeAgentTools] — registration logic kept verbatim (gates,
 * order, aliases) so the schema is byte-identical to the legacy list.
 * The mode filter then happens engine-side ([ToolRegistry.schemaFor]) —
 * the single place that decides what the model sees.
 */
object ToolSurfaceAdapter {

    fun buildRegistry(
        supportsImageInput: Boolean = true,
        memoryEnabled: Boolean = true,
        allowedTools: List<String>? = null,
        subagentsEnabled: Boolean = com.openminis.app.data.SubagentPrefs.isEnabled(),
        coreMemoryEnabled: Boolean = com.openminis.app.data.CoreMemoryPrefs.isEnabled(),
        rootShellEnabled: Boolean = com.openminis.app.data.RootShellPrefs.isEnabled(),
    ): ToolRegistry {
        val allow = AgentTools.expandAllowlist(allowedTools)

        fun permitted(name: String): Boolean = allow == null || name in allow

        fun register(
            registry: ToolRegistry,
            name: String,
            def: AgentToolDefinition,
        ) {
            if (permitted(name)) registry.register(EngineToolShell(name, def))
        }

        return ToolRegistry().apply {
            register(this, "shell_execute", AgentTools.shellExecuteDefinition())
            register(this, FileReadTool.NAME, FileReadTool.definition())
            register(this, FileWriteTool.NAME, FileWriteTool.definition())
            register(this, FileEditTool.NAME, FileEditTool.definition())
            if (supportsImageInput) {
                register(this, ReadImageTool.NAME, ReadImageTool.definition())
            }
            register(this, "browser_use", AgentTools.browserUseDefinition())
            // [T-web-search] Ungated like session_gc: read-only.
            register(this, WebSearchTool.NAME, WebSearchTool.definition())
            // [T-ask-user] Always available: it only ever waits for the user.
            register(this, AskUserTool.NAME, AskUserTool.definition())
            // [T-todo] Session checklist — rails + live progress.
            for (def in TodoTool.definitions()) {
                register(this, def.name, def)
            }
            // [T-mcp-first-class] ONE structured tool (list/tools/call).
            register(this, McpCallTool.NAME, McpCallTool.definition())
            // [T-webfetch] The light "read THIS page" path.
            register(this, WebFetchTool.NAME, WebFetchTool.definition())
            // [T-embedded-search] Native grep/glob over the bundled ripgrep.
            for (def in SearchTools.definitions()) {
                register(this, def.name, def)
            }
            // [T-spawn-subagent] OPT-IN by the user; session-scoped value
            // resolved by the caller.
            if (subagentsEnabled) {
                register(
                    this,
                    SubagentTools.SPAWN_TOOL_NAME,
                    SubagentTools.spawnSubagentDefinition(),
                )
                register(
                    this,
                    SubagentTools.SPAWN_MANY_TOOL_NAME,
                    SubagentTools.spawnManyDefinition(),
                )
                register(
                    this,
                    SubagentTools.LIST_AGENTS_TOOL_NAME,
                    SubagentTools.listAgentsDefinition(),
                )
                register(
                    this,
                    SubagentTools.RUN_GRAPH_TOOL_NAME,
                    SubagentTools.runGraphDefinition(),
                )
                // [T-task-board] Delegation needs sight of the team.
                register(
                    this,
                    SubagentTools.TASK_BOARD_TOOL_NAME,
                    SubagentTools.taskBoardDefinition(),
                )
            }
            register(this, TurnTimerTool.NAME, TurnTimerTool.definition())
            if (memoryEnabled) {
                register(this, "memory_write", AgentTools.memoryWriteDefinition())
                register(this, "memory_get", AgentTools.memoryGetDefinition())
                // [T-supermemory-tool] Semantic tier over the local service.
                register(this, "supermemory_search", AgentTools.supermemorySearchDefinition())
            }
            // [T-session-gc] Ungated: read-only dry-run by default.
            register(this, "session_gc", AgentTools.sessionGcDefinition())
            // [T-bg-tasks] The executor re-applies the shell policy gates.
            for (def in BgTaskTools.definitions()) {
                register(this, def.name, def)
            }
            // [T-letta-core-memory] App-level gate, independent of daily log.
            if (coreMemoryEnabled) {
                register(this, "memory_blocks_view", AgentTools.memoryBlocksViewDefinition())
                register(this, "memory_blocks_edit", AgentTools.memoryBlocksEditDefinition())
            }
            // [T-root-shell] Master-gated, default OFF.
            if (rootShellEnabled) {
                register(this, "root_shell", AgentTools.rootShellDefinition())
            }
        }
    }
}
