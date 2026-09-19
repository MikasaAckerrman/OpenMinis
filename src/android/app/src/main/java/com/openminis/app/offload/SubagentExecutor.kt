package com.openminis.app.offload

import android.content.Context
import com.openminis.app.data.model.AgentGraph
import com.openminis.app.data.model.AgentNode
import com.openminis.app.data.model.AgentRole
import com.openminis.app.data.model.GraphConfig
import com.openminis.app.MinisApp
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-spawn-subagent-executor] Executes runtime-spawned subagents.
 *
 * Design (Claude Code + OpenAI Agents SDK pattern):
 * - spawn() creates an ephemeral single-node graph with the requested role
 * - Runs through the existing AgentGraphRunner (gets parallelism, scope
 *   enforcement, timeout, retry machinery for free)
 * - Foreground: suspend until done, return result text
 * - Background: launch concurrently, result delivered via callback
 *
 * The ephemeral graph is registered (Runner loads by id) then deleted after.
 */
object SubagentExecutor {

    private const val TAG = "SubagentExecutor"

    /** Active background subagents: spawnId → role + task preview. */
    private val activeBackground = ConcurrentHashMap<String, String>()

    fun activeBackgroundCount(): Int = activeBackground.size

    /**
     * Spawn a subagent for [task] with [role].
     *
     * @param foreground true = suspend until done, return result.
     *                   false = run concurrently, return spawn id immediately.
     * @param onBackgroundResult invoked when a background subagent finishes.
     */
    suspend fun spawn(
        context: Context,
        role: String,
        task: String,
        foreground: Boolean,
        onBackgroundResult: ((spawnId: String, role: String, result: String) -> Unit)? = null,
    ): String {
        val app = context.applicationContext as MinisApp
        val agentRole = runCatching { AgentRole.valueOf(role) }
            .getOrElse {
                return "Unknown role '$role'. Valid: ${SubagentRoles.SPAWNABLE.joinToString()}"
            }

        val node = AgentNode(
            id = "spawn-${UUID.randomUUID().toString().take(8)}",
            role = agentRole,
            systemPrompt = promptForRole(agentRole, task),
            allowedTools = defaultToolsForRole(agentRole),
            maxTurns = 8,
        )
        val graph = AgentGraph(
            id = "ephemeral-${node.id}",
            name = "Spawn: ${agentRole.name.lowercase().replace('_', ' ')}",
            nodes = listOf(node),
            edges = emptyList(),
            entryNodeId = node.id,
            exitNodeIds = listOf(node.id),
            config = GraphConfig(
                maxParallelNodes = 1,
                defaultTimeoutMs = 180_000,
                defaultMaxOutputTokens = 8_192,
            ),
        )

        app.providerRepository.saveAgentGraph(graph)
        val spawnId = node.id

        return if (foreground) {
            try {
                val result = AgentGraphRunner.run(context, graph.id, task, taskId = spawnId)
                formatResult(role, result.finalHandoff ?: "", result.status.name)
            } finally {
                app.providerRepository.deleteAgentGraph(graph.id)
            }
        } else {
            activeBackground[spawnId] = "${agentRole.name}: ${task.take(80)}"
            val appRef = app
            val contextRef = context
            val graphRef = graph
            val roleRef = role
            val spawnRef = spawnId
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val result = AgentGraphRunner.run(contextRef, graphRef.id, task, taskId = spawnRef)
                    val text = formatResult(roleRef, result.finalHandoff ?: "", result.status.name)
                    onBackgroundResult?.invoke(spawnRef, roleRef, text)
                } finally {
                    activeBackground.remove(spawnRef)
                    runCatching { appRef.providerRepository.deleteAgentGraph(graphRef.id) }
                }
            }
            return "spawned: $spawnId (${agentRole.name.lowercase()}, running in background — result will arrive as notification)"
        }
    }

    /**
     * Run a registered graph (builtin or custom) as a tool call.
     */
    suspend fun runGraph(context: Context, graphId: String, input: String): String {
        return try {
            val result = AgentGraphRunner.run(context, graphId, input)
            formatResult(graphId, result.finalHandoff ?: "", result.status.name)
        } catch (e: Exception) {
            "Graph '$graphId' failed: ${e.message}"
        }
    }

    private fun formatResult(role: String, output: String, status: String): String {
        val trimmed = output.trim()
        return if (trimmed.isEmpty()) {
            "Subagent (${role.lowercase()}) completed with status $status but produced no output."
        } else {
            trimmed
        }
    }

    /** Role-specific system prompts for spawned subagents. */
    private fun promptForRole(role: AgentRole, task: String): String = when (role) {
        AgentRole.CODE_CORRECTNESS_REVIEWER -> """
            You are a code correctness reviewer. Your ONE job:
            $task

            Read the relevant code with file_read/shell_execute, trace the logic,
            and report:
            - Logic errors (wrong conditions, off-by-one, null paths)
            - Edge cases not handled
            - Race conditions or state inconsistencies
            - Anything that would produce wrong results

            Do NOT suggest style improvements. Only correctness.
            Format: a numbered list of findings, each with file:line and severity.
        """.trimIndent()

        AgentRole.SECURITY_REVIEWER -> """
            You are a security reviewer. Your ONE job:
            $task

            Examine the code/data for:
            - Injection vectors (SQL, command, path traversal)
            - Credential leaks (in logs, URLs, error messages)
            - Unsafe deserialization
            - Permission escalation paths

            Do NOT review style or performance. Only security.
            Format: numbered findings with attack scenario and severity.
        """.trimIndent()

        AgentRole.PERFORMANCE_REVIEWER -> """
            You are a performance reviewer. Your ONE job:
            $task

            Identify:
            - Unnecessary allocations in hot paths
            - O(n²) or worse algorithms where O(n) is possible
            - Blocking calls on the main thread
            - Memory leaks (listeners not removed, static references)

            Do NOT review correctness or style. Only performance.
            Format: numbered findings with measured impact estimate.
        """.trimIndent()

        AgentRole.CODEBASE_DISCOVERY -> """
            You are a codebase discovery agent. Your ONE job:
            $task

            Map the relevant code structure:
            - Key files and their responsibilities
            - Entry points and data flow
            - Dependencies between components
            - Where the relevant logic lives (exact file:line)

            Use file_read, shell_execute (grep/find) to explore.
            Format: a structured map of the relevant subsystem.
        """.trimIndent()

        AgentRole.SOLUTION_ARCHITECT -> """
            You are a solution architect. Your ONE job:
            $task

            Design the solution:
            - Propose 1-2 approaches with trade-offs
            - Identify files to create/modify
            - Define the data flow
            - Flag risks and unknowns

            Do NOT write code — describe the design.
            Format: approach description + file change list.
        """.trimIndent()

        AgentRole.SENIOR_IMPLEMENTER -> """
            You are a senior implementer. Your ONE job:
            $task

            Implement it:
            - Write the actual code changes
            - Use file_write/file_edit for modifications
            - Test with shell_execute
            - Report exactly what changed and why

            Format: summary of changes + file list.
        """.trimIndent()

        AgentRole.REQUIREMENTS_ANALYST -> """
            You are a requirements analyst. Your ONE job:
            $task

            Extract and structure the requirements:
            - What the user actually needs (not what they literally said)
            - Explicit constraints (time, resources, compatibility)
            - Implicit constraints (security, maintainability)
            - Success criteria

            Format: numbered requirements list with acceptance criteria.
        """.trimIndent()

        else -> """
            You are a ${role.name.lowercase().replace('_', ' ')} agent. Your ONE job:
            $task

            Focus exclusively on this task. Report your findings clearly.
            Do not do another role's work.
        """.trimIndent()
    }

    /** Scoped default tools per role (OpenAI Agents SDK pattern). */
    private fun defaultToolsForRole(role: AgentRole): List<String> = when (role) {
        AgentRole.SENIOR_IMPLEMENTER ->
            listOf("shell_execute", "file_read", "file_write", "file_edit", "browser_use")
        else ->
            listOf("shell_execute", "file_read", "browser_use")
    }
}

/** Role names exposed to the LLM for the spawn_subagent tool enum. */
object SubagentRoles {
    val SPAWNABLE = listOf(
        "REQUIREMENTS_ANALYST",
        "CODEBASE_DISCOVERY",
        "SOLUTION_ARCHITECT",
        "INDEPENDENT_TEST_DESIGNER",
        "SENIOR_IMPLEMENTER",
        "CODE_CORRECTNESS_REVIEWER",
        "SECURITY_REVIEWER",
        "PERFORMANCE_REVIEWER",
        "DEPENDENCY_GUARDIAN",
        "TEST_QUALITY_AUDITOR",
        "FINAL_GATEKEEPER",
        "DOCUMENTATION_AGENT",
    )
}
