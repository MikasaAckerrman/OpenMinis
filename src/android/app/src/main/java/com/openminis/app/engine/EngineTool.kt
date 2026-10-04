package com.openminis.app.engine

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.tools.ToolExecutionResult

/**
 * [T-engine-taxonomy] What a tool call may touch. This is the single input
 * the permission gate and the plan-mode schema filter reason about — a
 * tool's declared kind, not its name.
 *
 *  - READ     — observation only (file_read, read_image, memory_get).
 *  - WRITE    — mutates local state/files (file_write, file_edit, memory_write).
 *  - EXECUTE  — runs code or reaches outward with side-effect potential
 *               (shell_execute, bg_run, mcp). Evaluated per-call: a read-only
 *               shell command in PLAN mode is allowed, a mutating one is not.
 *  - NETWORK  — outbound reads (web_search, webfetch, browser_use).
 *  - META     — conversation-shaping tools with no world side effects
 *               (todo_*, ask_user, subagent delegation). Always allowed;
 *               children inherit the parent's mode.
 */
enum class MutationKind { READ, WRITE, EXECUTE, NETWORK, META }

/**
 * One tool as the engine sees it. Existing tool implementations under
 * tools/ are wrapped by thin adapters implementing this surface (the M6
 * migration); the registry and the loop only ever talk to [EngineTool].
 */
interface EngineTool {
    val name: String
    val mutation: MutationKind

    /** LLM-facing schema, rebuilt on demand (never cached by the engine). */
    fun definition(): AgentToolDefinition

    /** Execute one call. argsJson is the raw model-emitted arguments object. */
    suspend fun execute(argsJson: String, ctx: ToolContext): ToolExecutionResult
}

/**
 * Everything a tool call needs from its surroundings, injected — the engine
 * never reaches into globals. Constructed per call by the loop.
 */
class ToolContext(
    val sessionId: String,
    val logger: EngineLogger = EngineLogger.NONE,
)
