package com.openminis.app.offload

import android.content.Context
import com.openminis.app.data.model.AgentGraph
import com.openminis.app.data.model.AgentNode
import com.openminis.app.data.model.AgentRole
import com.openminis.app.data.model.GraphConfig
import com.openminis.app.data.model.GraphRunResult
import com.openminis.app.data.model.RunStatus
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
    private fun buildEphemeralGraph(
        agentRole: AgentRole,
        task: String,
        allowNesting: Boolean = true,
    ): Pair<AgentGraph, AgentNode> {
        val node = AgentNode(
            id = "spawn-${UUID.randomUUID().toString().take(8)}",
            role = agentRole,
            // [T-spawn-subagent-to] A single-node run has no next agent, so the
            // generic "TO: whichever role is next" leaves the model guessing a
            // value the parser will reject (it must be an exact AgentRole enum
            // name). Name the one target that always exists: the caller.
            systemPrompt = promptForRole(agentRole, task) + handoffToGuidance(agentRole),
            // [T-subagent-nesting] Depth cap, structurally: a worker's spawn
            // (allowNesting=false) strips the delegation tools from the
            // grandchild's schema — the orchestrator delegation lives ONE
            // level deep, exactly as designed.
            allowedTools = defaultToolsForRole(agentRole).let { tools ->
                if (allowNesting) tools
                else tools.filterNot { it == "spawn_subagent" || it == "spawn_many" }
            },
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
        /** [T-task-board] The spawner's session id — the team the run records under. */
        teamId: String? = null,
        /** [T-task-board] JSON array of predecessor task ids (enforced before the run). */
        dependsOnJson: String = "[]",
    ): String = try {
        // [T-task-board] Durable ledger: the run exists from THIS moment, not
        // from completion — a crash mid-run leaves a RUNNING row with history
        // instead of vanishing (the "what happened to my agent" answer).
        if (teamId != null) {
            AgentBoardRecorder.taskStarted(
                context, taskId = node.id, teamId = teamId,
                roleRequired = role, title = task.lineSequence().firstOrNull().orEmpty(),
                description = task, workspaceDir = null, dependsOnJson = dependsOnJson,
            )
        }
        val result = AgentGraphRunner.run(context, graph.id, task, taskId = node.id, ephemeral = true)
        if (teamId != null) {
            AgentBoardRecorder.taskFinished(
                context, taskId = node.id, teamId = teamId,
                assignedAgentId = null,
                succeeded = result.status == RunStatus.SUCCESS,
                result = formatResult(role, result),
            )
        }
        formatResult(role, result)
    } finally {
        app.providerRepository.deleteAgentGraph(graph.id)
    }

    /**
     * [T-spawn-crash] Shared background path for spawn()/spawnCustom().
     *
     * A raw `CoroutineScope(Dispatchers.IO)` has NO exception handler: before
     * this helper, any failure inside the launched block — the model run, the
     * result formatting, or the caller's callback — escaped to the default
     * handler and KILLED THE APP. A failed background subagent is a tool
     * error to report, not a crash. runCatching at every hop, finally for
     * the bookkeeping (ceiling slot, ephemeral graph config).
     */
    private fun launchBackground(
        context: Context,
        app: MinisApp,
        graph: AgentGraph,
        spawnId: String,
        role: String,
        task: String,
        onBackgroundResult: ((spawnId: String, role: String, result: String) -> Unit)?,
        /** [T-task-board] The spawner's session id — the team the run records under. */
        teamId: String? = null,
        /** [T-task-board] JSON array of predecessor task ids (enforced before the run). */
        dependsOnJson: String = "[]",
    ): String {
        activeBackground[spawnId] = "$role: ${task.take(80)}"
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                // [T-task-board] Durable ledger for background runs too — a
                // background agent that dies with the process leaves a
                // RUNNING row explaining WHERE it went instead of silence.
                if (teamId != null) {
                    AgentBoardRecorder.taskStarted(
                        context, taskId = spawnId, teamId = teamId,
                        roleRequired = role, title = task.lineSequence().firstOrNull().orEmpty(),
                        description = task, workspaceDir = null, dependsOnJson = dependsOnJson,
                    )
                }
                val text = try {
                    val result = AgentGraphRunner.run(context, graph.id, task, taskId = spawnId, ephemeral = true)
                    if (teamId != null) {
                        AgentBoardRecorder.taskFinished(
                            context, taskId = spawnId, teamId = teamId,
                            assignedAgentId = null,
                            succeeded = result.status == RunStatus.SUCCESS,
                            result = formatResult(role, result),
                        )
                    }
                    formatResult(role, result)
                } catch (e: Exception) {
                    com.openminis.app.logging.AppLogger.warning(
                        TAG, "background subagent $spawnId failed: ${e.message}",
                    )
                    if (teamId != null) {
                        AgentBoardRecorder.taskFinished(
                            context, taskId = spawnId, teamId = teamId,
                            assignedAgentId = null, succeeded = false,
                            result = "background run failed: ${e.message}",
                        )
                    }
                    "Subagent (${role.lowercase()}) background run failed: ${e.message}"
                }
                runCatching { onBackgroundResult?.invoke(spawnId, role, text) }
                    .onFailure {
                        com.openminis.app.logging.AppLogger.warning(
                            TAG, "background result callback failed: ${it.message}",
                        )
                    }
            } finally {
                activeBackground.remove(spawnId)
                runCatching { app.providerRepository.deleteAgentGraph(graph.id) }
            }
        }
        return "spawned: $spawnId ($role, running in background — result will arrive as notification)"
    }

    /**
     * [T-agent-file] Spawn a user-defined agent (role="custom:<name>"). Same
     * contract as spawn(): foreground waits, background notifies, ephemeral
     * cleanup. The custom file drives tools/model/budget; the runner role is
     * DOCUMENTATION_AGENT (not scope-guarded as non-writing, so a custom
     * agent MAY ship code when its tools allow it).
     */
    /**
     * [T-task-board] The dependency gate, shared by the builtin and custom
     * spawn paths (deep-analysis fix: customs previously bypassed it).
     * Returns the refusal text, or null when the spawn may proceed.
     */
    private suspend fun dependencyRefusal(
        context: Context,
        spawnerSessionId: String?,
        dependsOn: List<String>,
    ): String? {
        if (dependsOn.isEmpty()) return null
        if (spawnerSessionId.isNullOrBlank()) {
            return "depends_on refused: no team context (this spawn has no spawner session " +
                "to read the board of). Drop depends_on or spawn from the team's chat."
        }
        val teamTasks = com.openminis.app.data.db.ProviderDatabase
            .getInstance(context).agentBoardDao()
            .tasksForTeam(spawnerSessionId, limit = 100)
        val statusById = teamTasks.associate { it.id to it.status }
        val unmet = dependsOn.filter { statusById[it] != AgentBoardLogic.STATUS_COMPLETED }
        if (unmet.isEmpty()) return null
        val detail = unmet.joinToString { dep ->
            when (statusById[dep]) {
                null -> "$dep (unknown/pruned)"
                AgentBoardLogic.STATUS_RUNNING -> "$dep (still running)"
                AgentBoardLogic.STATUS_FAILED -> "$dep (FAILED — re-delegate it or drop the dependency)"
                else -> "$dep (${statusById[dep]})"
            }
        }
        return "spawn refused: dependencies not satisfied — $detail. " +
            "Call task_board to see the board, then re-spawn when they are COMPLETED."
    }

    private suspend fun spawnCustom(
        context: Context,
        app: MinisApp,
        name: String,
        task: String,
        foreground: Boolean,
        onBackgroundResult: ((spawnId: String, role: String, result: String) -> Unit)?,
        /** [T-subagent-nesting] Depth cap — see spawn(spawnerSessionId). */
        spawnerSessionId: String? = null,
        /** [T-task-board] JSON array of predecessor task ids (gate enforced). */
        dependsOnJson: String = "[]",
    ): String {
        val agent = AgentFileStore.find(context, name)
            ?: return "No custom agent '$name' in ${AgentFileStore.SANDBOX_DIR}. " +
                "Call list_agents to see what exists (or create the file with file_write)."
        val label = "custom:${agent.name}"
        // [T-subagent-nesting] Custom agents customarily serve as focused
        // workers: delegation is allowed ONLY from the main chat.
        val spawnerRole = spawnerSessionId
            ?.let { app.chatRepository.dao.agentWorkerRole(it) }
            ?.let { runCatching { AgentRole.valueOf(it) }.getOrNull() }
        val (graph, node) = buildCustomGraph(agent, task, spawnerRole == null)
        app.providerRepository.saveAgentGraph(graph)

        return if (foreground) {
            runSingle(context, app, graph, node, label, task, teamId = spawnerSessionId, dependsOnJson = dependsOnJson)
        } else {
            if (activeBackground.size >= MAX_BACKGROUND) {
                runCatching { app.providerRepository.deleteAgentGraph(graph.id) }
                return "spawn refused: $MAX_BACKGROUND background subagents are already " +
                    "running (phone ceiling). Wait for their result notifications first, " +
                    "then spawn again."
            }
            launchBackground(context, app, graph, node.id, label, task, onBackgroundResult, spawnerSessionId, dependsOnJson)
        }
    }

    /**
     * [T-agent-file] Resolve a role string (builtin enum or "custom:<name>")
     * to its ephemeral graph. Null = unresolvable (bad custom name).
     */
    private fun buildGraphFor(
        context: Context,
        role: String,
        task: String,
        allowNesting: Boolean = true,
    ): Pair<AgentGraph, AgentNode>? {
        if (role.startsWith("custom:")) {
            val agent = AgentFileStore.find(context, role.removePrefix("custom:").trim()) ?: return null
            return buildCustomGraph(agent, task, allowNesting)
        }
        val agentRole = runCatching { AgentRole.valueOf(role) }.getOrNull() ?: return null
        return buildEphemeralGraph(agentRole, task, allowNesting)
    }

    /** [T-agent-file] The ephemeral graph for a user-defined agent. */
    private fun buildCustomGraph(
        agent: AgentFileParser.AgentFile,
        task: String,
        allowNesting: Boolean = true,
    ): Pair<AgentGraph, AgentNode> {
        val node = AgentNode(
            id = "spawn-${UUID.randomUUID().toString().take(8)}",
            role = AgentRole.DOCUMENTATION_AGENT,
            systemPrompt = agent.instructions + handoffToGuidance(AgentRole.DOCUMENTATION_AGENT),
            // [T-subagent-nesting] A custom agent file may list spawn tools,
            // but the SAME structural cap applies: a spawn issued from a
            // worker session strips them — custom agents nest exactly one
            // level deep, like the builtin roles.
            allowedTools = (agent.tools ?: defaultToolsForRole(AgentRole.DOCUMENTATION_AGENT))
                .let { tools ->
                    if (allowNesting) tools
                    else tools.filterNot { it == "spawn_subagent" || it == "spawn_many" }
                },
            maxTurns = agent.maxTurns,
            modelEntryId = agent.modelEntryId.orEmpty(),
            modelRole = if (agent.modelEntryId != null) "" else (agent.modelRole ?: "analyst"),
        )
        val graph = AgentGraph(
            id = "ephemeral-${node.id}",
            name = "Agent: ${agent.name}",
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
    suspend fun spawnMany(
        context: Context,
        agents: List<SpawnSpec>,
        serial: Boolean = false,
        synthesize: Boolean = false,
        review: Boolean = false,
        /** [T-subagent-nesting] Depth cap — see spawn(spawnerSessionId). */
        spawnerSessionId: String? = null,
    ): String {
        if (agents.isEmpty()) return "spawn_many: no agents given"
        val app = context.applicationContext as MinisApp
        // [T-subagent-nesting] Resolve the delegation matrix once per batch.
        val spawnerRole = spawnerSessionId
            ?.let { app.chatRepository.dao.agentWorkerRole(it) }
            ?.let { runCatching { AgentRole.valueOf(it) }.getOrNull() }
        // [T-agent-file] Validation: builtin enum or an existing custom agent.
        for (spec in agents) {
            if (spec.role.startsWith("custom:")) {
                if (AgentFileStore.find(context, spec.role.removePrefix("custom:").trim()) == null) {
                    return "No custom agent '${spec.role.removePrefix("custom:")}' in " +
                        "${AgentFileStore.SANDBOX_DIR}. Call list_agents to see what exists."
                }
            } else if (runCatching { AgentRole.valueOf(spec.role) }.isFailure) {
                return "Unknown role '${spec.role}'. Valid: ${SubagentRoles.SPAWNABLE.joinToString()} " +
                    "or custom:<name> (see list_agents)"
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
        if (synthesize) sb.appendLine("+ synthesizer: ONE integrated answer from the board")
        if (review) sb.appendLine("+ reviewer: verifies the synthesis against the board")
        // [T-task-board] Cross-turn team memory: what THIS team already did
        // (durable Room board, not the in-run board below) — the orchestrator
        // returning next turn sees prior completions and failures and can
        // re-delegate the failed tail instead of redoing it blind.
        AgentBoardRecorder.teamSummary(context, spawnerSessionId ?: "")?.let { summary ->
            if (summary.isNotBlank()) {
                sb.appendLine(summary)
                sb.appendLine()
            }
        }
        sb.appendLine()

        // [T-task-board] The executor owns the board: workers never write it
        // (append-only is a code guarantee, not prompt discipline), each
        // batch sees the snapshot as of its start, and the file artifact
        // survives for the orchestrator to inspect later.
        val board = StringBuilder(TaskBoard.header(agents.map { it.role }))
        val batchId = "batch-${UUID.randomUUID().toString().take(6)}"

        coroutineScope {
            var order = 0
            plan.batches.forEach { batch ->
                // Snapshot BEFORE the batch: same-batch workers are
                // conflict-free independents — they see previous batches
                // only, which is the correct amount of context.
                val snapshot = board.toString()
                val sections = batch.map { task ->
                    val spec = agents[task.index]
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        parallelLimiter.withPermit {
                            // [T-subagent-nesting] spawnMany resolves the depth
                            // cap ONCE per batch (the spawner is fixed): workers
                            // spawned from a worker session get the delegation
                            // tools stripped, same rule as spawn().
                            val pair = buildGraphFor(context, spec.role, spec.task, mayDelegate(resolveRole(spec.role), spawnerRole))
                                ?: return@withPermit "No custom agent '${spec.role.removePrefix("custom:")}' " +
                                    "in ${AgentFileStore.SANDBOX_DIR} (call list_agents)."
                            val (graph, node) = pair
                            // [T-spawn-crash] Save INSIDE runCatching: a failed
                            // saveAgentGraph (DB hiccup) used to fail the async,
                            // cancel every sibling in the batch and throw away
                            // ALL results. One bad save is one bad agent.
                            runCatching {
                                app.providerRepository.saveAgentGraph(graph)
                                runSingle(
                                    context, app, graph, node, spec.role,
                                    TaskBoard.inject(spec.task, snapshot),
                                    teamId = spawnerSessionId,
                                )
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
                    board.append(TaskBoard.entry(order, batch[bi].role, text))
                }
            }
        }

        // [T-task-board] Optional synthesis + review on top of the batch —
        // the "quality" half of the parallel pipeline.
        if (synthesize) {
            val synth = runExtra(
                context, app, AgentRole.REQUIREMENTS_ANALYST,
                "Synthesize ONE integrated answer from the subagent results on the board below. " +
                    "Resolve contradictions explicitly, keep verified facts, flag conflicts and " +
                    "gaps. Answer in the language of the original tasks.\n\n" +
                    board.toString().take(9000),
            )
            sb.appendLine("### SYNTHESIS (requirements_analyst)")
            sb.appendLine(synth)
            sb.appendLine()
            board.append(TaskBoard.entry(0, "SYNTHESIS", synth))
        }
        if (review) {
            val verdict = runExtra(
                context, app, AgentRole.CODE_CORRECTNESS_REVIEWER,
                "Verify the SYNTHESIS below against the findings on the board. List concrete " +
                    "mistakes, contradictions or unsupported claims with the agent number they " +
                    "come from; write CONFIRMED if the synthesis holds.\n\n" +
                    board.toString().take(9000),
            )
            sb.appendLine("### REVIEW (code_correctness_reviewer)")
            sb.appendLine(verdict)
            sb.appendLine()
        }

        val boardPath = writeBoardFile(batchId, board.toString())
        if (boardPath != null) sb.appendLine("Board artifact: $boardPath")
        sb.appendLine("spawn_many finished.")
        return sb.toString()
    }

    /** [T-task-board] One builtin ephemeral agent over a ready-made task. */
    private suspend fun runExtra(
        context: Context,
        app: MinisApp,
        role: AgentRole,
        task: String,
    ): String {
        val (graph, node) = buildEphemeralGraph(role, task)
        app.providerRepository.saveAgentGraph(graph)
        return runSingle(context, app, graph, node, role.name.lowercase(), task)
    }

    /** [T-task-board] Persist the final board as a workspace artifact. */
    private fun writeBoardFile(batchId: String, board: String): String? = try {
        val host = com.openminis.app.sandbox.PRootKernel
            .resolveHostPath("/var/minis/workspace/$batchId/BOARD.md") ?: return null
        host.parentFile?.mkdirs()
        host.writeText(board)
        "/var/minis/workspace/$batchId/BOARD.md"
    } catch (_: Exception) {
        null
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
        /**
         * [T-subagent-nesting] The CALLER's session id — the structural depth
         * cap. When the caller is itself an agent worker (a spawned
         * subagent session), the child's tool set is stripped of the
         * delegation tools (spawn_subagent / spawn_many): an orchestrator
         * spawned from the MAIN chat can delegate (2-level tree, the Cursor
         * pattern), but a worker's spawn cannot — exactly ONE nesting
         * level, no counters, no runaway recursion.
         */
        spawnerSessionId: String? = null,
        /**
         * [T-task-board] Team task ids this run must wait for — enforced, not
         * advisory: the spawn is refused while any listed task is not
         * COMPLETED, and the refusal names the unmet ones.
         */
        dependsOn: List<String> = emptyList(),
    ): String {
        val app = context.applicationContext as MinisApp
        // [T-agent-file] role="custom:<name>" routes to a user-defined agent
        // file — new agents without code, the Codex v2 pattern.
        if (role.startsWith("custom:")) {
            // [T-task-board] Deep-analysis fix: the custom path previously
            // bypassed the dependency gate — the schema promises enforcement
            // for EVERY spawn; a custom agent is not a dep loophole.
            dependencyRefusal(context, spawnerSessionId, dependsOn)?.let { return it }
            return spawnCustom(
                context, app, role.removePrefix("custom:").trim(), task, foreground, onBackgroundResult,
                spawnerSessionId,
                if (dependsOn.isEmpty()) "[]" else
                    dependsOn.joinToString(prefix = "[", postfix = "]") { "\"${it}\"" },
            )
        }
        val agentRole = runCatching { AgentRole.valueOf(role) }
            .getOrElse {
                return "Unknown role '$role'. Valid: ${SubagentRoles.SPAWNABLE.joinToString()} " +
                    "or custom:<name> (call list_agents to see user-defined agents)"
            }

        // [T-task-board] Enforced dependency gate: the orchestrator declared
        // predecessors; while any is unfinished (or unknown/pruned) this spawn
        // does not run — better a deterministic refusal naming the wait-set
        // than a worker starting on top of half-done input.
        dependencyRefusal(context, spawnerSessionId, dependsOn)?.let { return it }

        // [T-subagent-nesting] Resolve the delegation matrix BEFORE building
        // the graph: agentWorkerRole is a suspend DB read (IO-safe here —
        // spawn is already on Dispatchers.IO), returning null for the main
        // chat. See mayDelegate() for the matrix itself.
        val spawnerRole = spawnerSessionId
            ?.let { app.chatRepository.dao.agentWorkerRole(it) }
            ?.let { runCatching { AgentRole.valueOf(it) }.getOrNull() }
        val allowNesting = mayDelegate(agentRole, spawnerRole)
        val (graph, node) = buildEphemeralGraph(agentRole, task, allowNesting)
        app.providerRepository.saveAgentGraph(graph)
        val spawnId = node.id

        val dependsJson = if (dependsOn.isEmpty()) "[]" else
            dependsOn.joinToString(prefix = "[", postfix = "]") { "\"${it}\"" }
        return if (foreground) {
            runSingle(context, app, graph, node, role, task, teamId = spawnerSessionId, dependsOnJson = dependsJson)
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
            return launchBackground(context, app, graph, spawnId, role, task, onBackgroundResult, spawnerSessionId, dependsJson)
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

            You may spawn_subagent (INDEPENDENT_TEST_DESIGNER) for ONE focused
            runtime check when a finding needs execution proof you cannot get
            by reading. Your delegated checks run at depth 3 — they are leaf
            agents and cannot delegate further; give them the complete
            question, file paths and expected outcome.
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

            You may spawn_subagent for ONE focused verification of a suspected
            vector (e.g. INDEPENDENT_TEST_DESIGNER to reproduce it). Delegated
            agents are leaves — give them the complete context in the task.
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

            You may spawn_subagent for ONE focused measurement when a finding
            needs a real profile rather than an estimate. Delegated agents are
            leaves — include the exact setup in the task.
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

        // [T-subagent-nesting] Middle-orchestrator hesitation is a documented
        // failure mode (Cursor's team confirmed it): an L1 orchestrator that
        // CAN delegate often doesn't — it "thinks it can handle it itself",
        // burns its whole turn budget on worker-level work and the 2-level
        // tree never forms. The fix is an explicit MANDATE, not a hint:
        // independent subtasks MUST be delegated, doing them inline is the
        // mistake the prompt forbids.
        AgentRole.ORCHESTRATOR -> """
            You are an orchestrator. Your ONE job: DELEGATE, don't implement.
            $task

            First split the task into independent subtasks. Then, for each:
            - If it is self-contained, spawn a subagent (spawn_subagent, or
              spawn_many when several subtasks are independent — they then run
              in PARALLEL). Doing independent subtasks yourself is a MISTAKE —
              your turn budget is for planning, splitting and synthesis only.
            - Roles: SENIOR_IMPLEMENTER writes code; CODE_CORRECTNESS_REVIEWER /
              SECURITY_REVIEWER / PERFORMANCE_REVIEWER audit it; CODEBASE_DISCOVERY
              maps unknown code first when context is missing.
            - Give each subagent a COMPLETE task description: file paths, the
              exact change, acceptance criteria. Workers see nothing of your
              conversation — the task text is their entire context.
            - Never spawn ORCHESTRATOR as your worker (workers cannot delegate;
              that is by design, not a limitation to work around).

            Synthesize the workers' results into one final answer. If a worker
            failed or its result contradicts the task, say so plainly instead
            of papering over it.
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
        // [T-subagent-nesting] The orchestrator is the entry delegator: it
        // plans, splits the task and spawns worker subagents (the Cursor
        // 2-level tree pattern). Reviewers may arm ONE focused verifier for
        // findings that need runtime proof (depth 3: ORCHESTRATOR ->
        // REVIEWER -> leaf). The matrix that grants these tools lives in
        // mayDelegate() — see the depth-cap comment there.
        AgentRole.ORCHESTRATOR ->
            listOf("shell_execute", "file_read", "spawn_subagent", "spawn_many", "task_board")
        AgentRole.CODE_CORRECTNESS_REVIEWER,
        AgentRole.SECURITY_REVIEWER,
        AgentRole.PERFORMANCE_REVIEWER,
        AgentRole.TEST_QUALITY_AUDITOR ->
            listOf("shell_execute", "file_read", "browser_use", "spawn_subagent")
        else ->
            listOf("shell_execute", "file_read", "browser_use")
    }

    private fun resolveRole(role: String): AgentRole? =
        runCatching { AgentRole.valueOf(role) }.getOrNull()

    /**
     * [T-subagent-nesting] The delegation capability matrix — the structural
     * depth cap, expressible with data the DB already holds (the worker
     * marker + the spawner's role), no runtime counters:
     *
     *  - MAIN chat (spawnerRole == null): may arm ORCHESTRATOR (the entry
     *    delegator) and REVIEWERS (a user-spawned reviewer can pull in a
     *    focused verifier).
     *  - ORCHESTRATOR: may additionally arm REVIEWER children — depth 3:
     *    ORCHESTRATOR -> REVIEWER -> leaf. The reviewer delegates a single
     *    verification without dragging the intermediate output back to main.
     *  - Everything else is a leaf: no role granted by a worker spawner can
     *    delegate, so no chain can pass depth 3 — an orchestrator cannot arm
     *    another orchestrator, a reviewer cannot arm a reviewer, a worker
     *    arms nobody. Uncontrolled fan-out is structurally impossible.
     */
    private fun mayDelegate(childRole: AgentRole?, spawnerRole: AgentRole?): Boolean {
        val reviewers = setOf(
            AgentRole.CODE_CORRECTNESS_REVIEWER,
            AgentRole.SECURITY_REVIEWER,
            AgentRole.PERFORMANCE_REVIEWER,
            AgentRole.TEST_QUALITY_AUDITOR,
        )
        return when (spawnerRole) {
            null -> childRole == AgentRole.ORCHESTRATOR || (childRole != null && childRole in reviewers)
            AgentRole.ORCHESTRATOR -> childRole != null && childRole in reviewers
            else -> false
        }
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
