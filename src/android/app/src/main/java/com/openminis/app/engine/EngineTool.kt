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
 *
 * [T-m6-tool-context] The context carries the executor callback. The
 * platform executor (the ChatViewModel dispatch) is reachable only through
 * [dispatch] — the engine never learns where the code actually runs.
 *
 * [T-m11-verify] The pre-declared `gate`/`hooks`/`logger` fields were
 * removed by the dead-code sweep: they had ZERO readers (hooks run in the
 * platform dispatcher BELOW dispatch; the gate lives engine-side in
 * schemaFor; logging is the loop's concern). The loop swap reintroduces
 * a seam ONLY when a consumer exists — fields are not declared on credit.
 */
class ToolContext(
    val sessionId: String,
    /**
     * The single route to the real implementation behind a tool name —
     * the thin-adapter seam of M6. Adapters call it; the loop injects the
     * platform dispatch. Default refuses: an engine-only construction
     * (tests, previews) cannot accidentally execute anything.
     */
    val dispatch: suspend (toolName: String, argsJson: String) -> ToolExecutionResult =
        { name, _ ->
            throw IllegalStateException("no dispatch wired for tool '$name' — engine-only context")
        },
)
