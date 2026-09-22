package com.openminis.app.offload

import android.content.Context
import com.openminis.app.data.model.AgentGraph
import com.openminis.app.data.model.AgentNode
import com.openminis.app.data.model.AgentRole
import com.openminis.app.data.model.GraphConfig
import com.openminis.app.data.model.GraphRunResult
import com.openminis.app.MinisApp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    /** [T-spawn-many] Phone ceilings: parallel tool agents + background spawns. */
    const val MAX_PARALLEL = 3
    const val MAX_BACKGROUND = 3
    private val parallelLimiter = Semaphore(MAX_PARALLEL)

    /** [T-spawn-many] One agent of a spawn_many batch. */
    data class SpawnSpec(val role: String, val task: String)

    /**
     * [T-spawn-many] The ephemeral single-node graph every spawned subagent
     * runs in (was inline in spawn()).
     */
    private fun buildEphemeralGraph(agentRole: AgentRole, task: String): Pair<AgentGraph, AgentNode> {
        val node = AgentNode(
            id = "spawn-${UUID.randomUUID().toString().take(8)}",
            role = agentRole,
            // [T-spawn-subagent-to] A single-node run has no next agent, so the
            // generic "TO: whichever role is next" leaves the model guessing a
            // value the parser will reject (it must be an exact AgentRole enum
            // name). Name the one target that always exists: the caller.
            systemPrompt = promptForRole(agentRole, task) + handoffToGuidance(agentRole),
            allowedTools = defaultToolsForRole(agentRole),
            maxTurns = 8,
            modelRole = modelRoleFor(agentRole),
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
        return graph to node
    }

    /**
     * Run one ephemeral subagent to completion. Shared by spawn()'s foreground
     * path and spawnMany(): saves the graph, runs ephemeral, formats, deletes
     * the graph config. Worker-session deletion is the runner's job
     * (ephemeral=true).
     */
    private suspend fun runSingle(
        context: Context,
        app: MinisApp,
        graph: AgentGraph,
        node: AgentNode,
        role: String,
        task: String,
    ): String = try {
        val result = AgentGraphRunner.run(context, graph.id, task, taskId = node.id, ephemeral = true)
        formatResult(role, result)
    } finally {
        app.providerRepository.deleteAgentGraph(graph.id)
    }

    /**
     * [T-spawn-many] Claude Code's "N Task calls, one join point", with the
     * conflict safety the prompt cannot guarantee:
     *
     *  1. TaskConflictDetector extracts file paths from every task text and
     *     serializes tasks that touch the same file/dir (two writers on one
     *     file is silent data loss, not a speedup).
     *  2. Independent tasks run concurrently, capped at [MAX_PARALLEL]
     *     (phone: memory, provider rate limits).
     *  3. One tool result carries ALL answers, numbered, so the orchestrator
     *     sees the batch as a single decision point.
     */
    suspend fun spawnMany(context: Context, agents: List<SpawnSpec>, serial: Boolean = false): String {
        if (agents.isEmpty()) return "spawn_many: no agents given"
        val app = context.applicationContext as MinisApp
        val roles = agents.map { spec ->
            runCatching { AgentRole.valueOf(spec.role) }.getOrElse {
                return "Unknown role '${spec.role}'. Valid: ${SubagentRoles.SPAWNABLE.joinToString()}"
            }
        }
        val tasks = agents.mapIndexed { i, spec -> TaskConflictDetector.Task(i, spec.role, spec.task) }
        val plan = if (serial) {
            // Explicit serial: one task per batch, array order preserved.
            TaskConflictDetector.Plan(tasks.map { listOf(it) }, emptyList())
        } else {
            TaskConflictDetector.plan(tasks)
        }
        val sb = StringBuilder()
        sb.appendLine(
            "spawn_many: ${agents.size} agent(s) in ${plan.batches.size} batch(es) " +
                "(${plan.batches.joinToString(" + ") { it.size.toString() }})" +
                if (plan.isFullyParallel) " — all independent, running in parallel" else "",
        )
        plan.conflictNotes.forEach { sb.appendLine("⚠ $it") }
        sb.appendLine()
        coroutineScope {
            var order = 0
            plan.batches.forEach { batch ->
                val sections = batch.map { task ->
                    val spec = agents[task.index]
                    val agentRole = roles[task.index]
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        parallelLimiter.withPermit {
                            val (graph, node) = buildEphemeralGraph(agentRole, spec.task)
                            app.providerRepository.saveAgentGraph(graph)
                            runCatching {
                                runSingle(context, app, graph, node, spec.role, spec.task)
                            }.getOrElse { e ->
                                "Subagent (${spec.role.lowercase()}) error: ${e.message}"
                            }
                        }
                    }
                }.map { it.await() }
                sections.forEachIndexed { bi, text ->
                    order++
                    sb.appendLine("### $order. ${batch[bi].role}")
                    sb.appendLine(text)
                    sb.appendLine()
                }
            }
            sb.appendLine("spawn_many finished: $order result(s).")
        }
        return sb.toString()
    }

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

        val (graph, node) = buildEphemeralGraph(agentRole, task)
        app.providerRepository.saveAgentGraph(graph)
        val spawnId = node.id

        return if (foreground) {
            runSingle(context, app, graph, node, role, task)
        } else {
            // [T-spawn-many] Phone ceiling: unbounded background spawns would
            // multiply live sessions, VMs and model calls on a device with
            // one radio and one battery.
            if (activeBackground.size >= MAX_BACKGROUND) {
                runCatching { app.providerRepository.deleteAgentGraph(graph.id) }
                return "spawn refused: $MAX_BACKGROUND background subagents are already " +
                    "running (phone ceiling). Wait for their result notifications first, " +
                    "then spawn again."
            }
            activeBackground[spawnId] = "${agentRole.name}: ${task.take(80)}"
            val appRef = app
            val contextRef = context
            val graphRef = graph
            val roleRef = role
            val spawnRef = spawnId
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val result = AgentGraphRunner.run(contextRef, graphRef.id, task, taskId = spawnRef, ephemeral = true)
                    val text = formatResult(roleRef, result)
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
            // [T-spawn-subagent-ephemeral] A graph invoked as a TOOL narrates
            // through the tool result and the live progress card in the
            // originating chat; a showcase session would be an invisible,
            // undeletable row (see AgentGraphRunner.run).
            val result = AgentGraphRunner.run(context, graphId, input, ephemeral = true)
            formatResult(graphId, result)
        } catch (e: Exception) {
            "Graph '$graphId' failed: ${e.message}"
        }
    }

    /**
     * [T-spawn-subagent-ephemeral] Prefer the handoff; fall back to the raw
     * answer when the worker botched the HANDOFF block. Reporting "no output"
     * after the model was called and paid for throws away work the caller
     * could still use.
     */
    private fun formatResult(role: String, result: GraphRunResult): String {
        val handoff = result.finalHandoff?.trim().orEmpty()
        if (handoff.isNotEmpty()) return handoff
        val raw = result.lastExitResponse?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            return "Subagent (${role.lowercase()}) status ${result.status} — no valid " +
                "HANDOFF block, raw answer follows:\n\n${raw.take(4000)}"
        }
        val error = result.error?.let { " Error: $it" } ?: ""
        return "Subagent (${role.lowercase()}) completed with status ${result.status} " +
            "but produced no output.$error"
    }

    /** [T-spawn-subagent-to] See AgentNode.systemPrompt. */
    private fun handoffToGuidance(role: AgentRole): String =
        "\n\nHANDOFF: you are the ONLY agent in this run — there is no next agent. " +
            "In your final handoff block write FROM: ${role.name}, TO: ORCHESTRATOR " +
            "(the agent that spawned you), and STATUS: COMPLETE with your findings " +
            "listed under DELIVERABLES."

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
        // Their deliverables ARE files (test files, documentation) — without
        // file tools these roles could only describe the artifact they were
        // spawned to produce.
        AgentRole.INDEPENDENT_TEST_DESIGNER,
        AgentRole.DOCUMENTATION_AGENT ->
            listOf("shell_execute", "file_read", "file_write", "file_edit")
        else ->
            listOf("shell_execute", "file_read", "browser_use")
    }

    /**
     * Map a spawnable [AgentRole] to a model-role key the resolver understands
     * (planner | analyst | architect | coder | reviewer | tester — the keys
     * AgentKeysCollection.VALID_ROLES and BuiltinGraphs use).
     *
     * Without SOME model source, AgentGraph.validate() rejects the ephemeral
     * graph ("needs modelEntryId or modelRole") and every spawn_subagent call
     * dies at saveAgentGraph before a single token is spent — the bug that
     * made runtime subagents unusable.
     *
     * modelRole (not a pinned modelEntryId) is deliberate: resolution then
     * goes through ProviderRepository.resolveModelEntryForRole, which honours
     * the user's per-role keys and Settings and otherwise falls back to the
     * model the user already chats with — "agents use the model I chat with
     * unless I say otherwise".
     */
    private fun modelRoleFor(role: AgentRole): String = when (role) {
        AgentRole.REQUIREMENTS_ANALYST -> "planner"
        AgentRole.CODEBASE_DISCOVERY -> "analyst"
        AgentRole.SOLUTION_ARCHITECT -> "architect"
        AgentRole.INDEPENDENT_TEST_DESIGNER -> "tester"
        AgentRole.TEST_QUALITY_AUDITOR -> "tester"
        AgentRole.SENIOR_IMPLEMENTER -> "coder"
        AgentRole.DOCUMENTATION_AGENT -> "analyst"
        AgentRole.CODE_CORRECTNESS_REVIEWER -> "reviewer"
        AgentRole.SECURITY_REVIEWER -> "reviewer"
        AgentRole.PERFORMANCE_REVIEWER -> "reviewer"
        AgentRole.DEPENDENCY_GUARDIAN -> "reviewer"
        AgentRole.FINAL_GATEKEEPER -> "reviewer"
        AgentRole.ORCHESTRATOR -> "planner"
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
