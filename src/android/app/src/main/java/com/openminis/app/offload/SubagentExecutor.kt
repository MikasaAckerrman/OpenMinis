package com.openminis.app.offload

import android.content.Context
import com.openminis.app.data.model.AgentGraph
import com.openminis.app.data.model.AgentNode
import com.openminis.app.data.model.AgentRole
import com.openminis.app.data.model.GraphConfig
import com.openminis.app.data.model.GraphRunResult
import com.openminis.app.data.model.RunStatus
import com.openminis.app.MinisApp
import kotlinx.coroutines.Job
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
    /** [event-matrix M2] Assembled spawn_many result cap + per-handoff cap. */
    private const val RESULT_CAP_CHARS = 24_000
    private const val HANDOFF_CAP = 6_000

    /**
     * Active background subagents: spawnId → preview + the job that runs it.
     *
     * [T-subagent-cancel-by-id] The job is kept here so a background agent
     * can be cancelled by its spawn id. The map used to hold the preview
     * string alone, which meant the only way to stop a background agent was
     * to let it finish - a spawn that had gone wrong kept its slot, its
     * model calls and its share of the concurrency budget until the whole
     * task was over.
     */
    private val activeBackground = ConcurrentHashMap<String, ActiveBackground>()

    /**
     * [T-graph-cancel] The graph runs that can be stopped.
     *
     * A separate registry from the background spawns: they are different
     * things with different limits, and one run of one kind must not be
     * able to hide a run of the other kind behind the same id.
     */
    private val graphScopes = AgentGraphScopes()

    private class ActiveBackground(val preview: String, val job: kotlinx.coroutines.Job)

    /** [T-spawn-many] Phone ceilings: parallel tool agents + background spawns. */
    // [T-parallel-agents] The ceilings come from AgentConcurrency so the
    // executor and the scheduler agree. They used to be fixed at 3 while the
    // scheduler allowed a wave of 4, which left a spawn waiting behind a
    // gate nobody planned for.
    val MAX_PARALLEL: Int = com.openminis.app.engine.AgentConcurrency.parallel
    val MAX_BACKGROUND: Int = com.openminis.app.engine.AgentConcurrency.background
    private val parallelLimiter = Semaphore(MAX_PARALLEL)

    /**
     * [T-subagent-background-ceiling] The background ceiling, enforced.
     *
     * The check used to be `activeBackground.size >= MAX_BACKGROUND` followed
     * by the launch, which is a test-then-act: two spawns that arrive at the
     * same time both see a size below the ceiling and both start, so the
     * ceiling was not a ceiling. On a phone, background agents are live
     * model calls, live view models and live sessions, and the whole point
     * of the limit is to bound them.
     *
     * A semaphore makes the test and the reservation one step, the same way
     * parallelLimiter already does for the foreground limit.
     */
    private val backgroundLimiter = Semaphore(MAX_BACKGROUND)

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
            id = "spawn-${UUID.randomUUID().toString().take(16)}",
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
                else tools.filterNot { it == "spawn_subagent" || it == "spawn_many" || it == "cancel_subagent" }
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
        /**
         * [T-agent-card-live] The run's task id, the moment the run starts.
         *
         * The chat draws a line per agent, and the line has to show what
         * that agent is doing RIGHT NOW. The live progress of a run is
         * filed under its task id, so the line cannot find it without
         * being told the id. It is the same id the board row is filed
         * under, and the same one the progress registry is keyed by.
         */
        onStarted: ((taskId: String) -> Unit)? = null,
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
        // [T-agent-card-live] The id is known BEFORE the run starts, so
        // the line can be attached to the live progress from the first
        // moment - not from the moment the agent says something.
        onStarted?.invoke(node.id)
        // [T-board-cancelled-status] A run that is cancelled must still
        // close its task. Before, the cancellation propagated straight out
        // of this function and the board row stayed RUNNING for good - a
        // zombie that no one would ever finish.
        val result = try {
            AgentGraphRunner.run(context, graph.id, task, taskId = node.id, ephemeral = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (teamId != null) {
                AgentBoardRecorder.taskFinished(
                    context, taskId = node.id, teamId = teamId,
                    assignedAgentId = null, succeeded = false,
                    result = "run cancelled",
                    cancelled = true,
                )
            }
            throw e
        }
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
        parentJob: kotlinx.coroutines.Job? = null,
    ): String {
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.IO + (parentJob ?: kotlinx.coroutines.Job())
        )
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // [T-subagent-background-ceiling] A cancelled run is not a
                    // failed run.
                    //
                    // CancellationException is an Exception, so the general
                    // handler below would swallow it: the cancellation would
                    // stop propagating, which is the one thing structured
                    // concurrency depends on, and the task would be recorded
                    // on the board as FAILED - a claim about the run that is
                    // not true.
                    //
                    // The board is told the truth first, so the row does not
                    // stay RUNNING for a run that no longer exists, and then
                    // the cancellation is rethrown.
                    if (teamId != null) {
                        AgentBoardRecorder.taskFinished(
                            context, taskId = spawnId, teamId = teamId,
                            assignedAgentId = null, succeeded = false,
                            result = "background run cancelled",
                            // [T-board-cancelled-status] The user stopped
                            // it. Recording that as FAILED would say the run
                            // broke, which is not what happened.
                            cancelled = true,
                        )
                    }
                    throw e
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
                backgroundLimiter.release()
                runCatching { app.providerRepository.deleteAgentGraph(graph.id) }
            }
        }
        activeBackground[spawnId] = ActiveBackground("$role: ${task.take(80)}", job)
        job.start()
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
        val now = System.currentTimeMillis()
        if (dependsOn.isEmpty()) return null
        if (spawnerSessionId.isNullOrBlank()) {
            return "depends_on refused: no team context (this spawn has no spawner session " +
                "to read the board of). Drop depends_on or spawn from the team's chat."
        }
        val teamTasks = com.openminis.app.data.db.ProviderDatabase
            .getInstance(context).agentBoardDao()
            .tasksByIds(dependsOn)
        val unmet = dependsOn.filter { dep ->
            teamTasks.find { it.id == dep }?.let { it.status != AgentBoardLogic.STATUS_COMPLETED } ?: true
        }
        if (unmet.isEmpty()) return null
        val detail = unmet.joinToString { dep ->
            val row = teamTasks.find { it.id == dep }
            // [T-board-stale-exact] A run still visible in this process is
            // alive, however old its row is: the row's updated_at is a birth
            // stamp, not a heartbeat, and treating it as one told the
            // orchestrator to re-spawn an agent that was still working.
            val stale = row != null &&
                AgentBoardLogic.isStale(
                    status = row.status,
                    updatedAt = row.updatedAt,
                    nowMs = now,
                    hasLiveProgress = AgentRunProgress.snapshots.value.containsKey(dep),
                )
            when {
                row == null -> "$dep (unknown/pruned)"
                stale -> "$dep (RUNNING >1h — the run likely died with its process; re-spawn it or drop the dependency)"
                row.status == AgentBoardLogic.STATUS_RUNNING -> "$dep (still running)"
                row.status == AgentBoardLogic.STATUS_FAILED -> "$dep (FAILED — re-delegate it or drop the dependency)"
                else -> "$dep (${row.status})"
            }
        }
        return "spawn refused: dependencies not satisfied — $detail. " +
            "Call task_board to see the board, then re-spawn when they are COMPLETED."
    }

    /**
     * [T-task-board] Deps list → board JSON. Single source: the string was
     * built in two places (custom + builtin paths) — duplicated builders of
     * the same wire format drift, and a drifted one corrupts the board's
     * dependency graph silently.
     */
    private fun depsJson(dependsOn: List<String>): String =
        if (dependsOn.isEmpty()) "[]" else
            dependsOn.joinToString(prefix = "[", postfix = "]") { "\"${it}\"" }

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
            if (!backgroundLimiter.tryAcquire()) {
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
        val agentRole = resolveRole(role) ?: return null
        return buildEphemeralGraph(agentRole, task, allowNesting)
    }

    /** [T-agent-file] The ephemeral graph for a user-defined agent. */
    private fun buildCustomGraph(
        agent: AgentFileParser.AgentFile,
        task: String,
        allowNesting: Boolean = true,
    ): Pair<AgentGraph, AgentNode> {
        val node = AgentNode(
            id = "spawn-${UUID.randomUUID().toString().take(16)}",
            role = AgentRole.DOCUMENTATION_AGENT,
            systemPrompt = agent.instructions + handoffToGuidance(AgentRole.DOCUMENTATION_AGENT),
            // [T-subagent-nesting] A custom agent file may list spawn tools,
            // but the SAME structural cap applies: a spawn issued from a
            // worker session strips them — custom agents nest exactly one
            // level deep, like the builtin roles.
            allowedTools = (agent.tools ?: defaultToolsForRole(AgentRole.DOCUMENTATION_AGENT))
                .let { tools ->
                    if (allowNesting) tools
                    else tools.filterNot { it == "spawn_subagent" || it == "spawn_many" || it == "cancel_subagent" }
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
        /**
         * [T-review-gate] The last checkpoint of a batch. The reviewer says
         * whether the synthesis holds; the gatekeeper says whether the work
         * may be called done, and names the blockers it can point at in the
         * code. It fixes nothing itself.
         */
        gate: Boolean = false,
        /** [T-subagent-nesting] Depth cap — see spawn(spawnerSessionId). */
        spawnerSessionId: String? = null,
        /**
         * [T-subagent-block-live] Called once per agent, the moment that
         * agent is done.
         *
         * The chat draws a line per agent, and the line has to stop when
         * THAT agent stops - not when the batch is through, which for
         * agents of different length is a different moment entirely. So
         * the call is made from inside the agent's own coroutine, where
         * its result exists.
         *
         * It runs on an IO thread: whatever receives it has to reach the
         * main thread on its own.
         */
        onAgentDone: (index: Int, role: String, result: String, ok: Boolean) -> Unit =
            { _, _, _, _ -> },
        /**
         * [T-agent-card-live] The run's task id, per agent, the moment that
         * agent starts. The batch runs agents concurrently, so each one
         * reports its own id - the line for agent N is filed under agent
         * N's progress, not under the batch's.
         */
        onAgentStarted: ((index: Int, taskId: String) -> Unit)? = null,
    ): String {
        if (agents.isEmpty()) return "spawn_many: no agents given"
        val app = context.applicationContext as MinisApp
        // [T-subagent-nesting] Resolve the delegation matrix once per batch.
        val spawnerRole = spawnerSessionId
            ?.let { app.chatRepository.dao.agentWorkerRole(it) }
            ?.let { runCatching { AgentRole.valueOf(it) }.getOrNull() }
        // [T-agent-file] Validation: builtin enum or an existing custom agent.
        // [event-matrix G12] Same lenient resolution as the single-spawn path
        // — batch validation previously used strict valueOf, so a lowercase
        // role passed the single spawn and failed the batch (path
        // inconsistency, the A8xA3 class).
        for (spec in agents) {
            if (spec.role.startsWith("custom:")) {
                if (AgentFileStore.find(context, spec.role.removePrefix("custom:").trim()) == null) {
                    return "No custom agent '${spec.role.removePrefix("custom:")}' in " +
                        "${AgentFileStore.SANDBOX_DIR}. Call list_agents to see what exists."
                }
            } else if (resolveRole(spec.role) == null) {
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
                            // [T-spawn-crash + event-matrix fix] EVERYTHING
                            // fallible is inside runCatching: buildGraphFor
                            // reads agent FILES (IOException possible) and used
                            // to sit OUTSIDE the guard — one file-read throw
                            // failed the async, cancelled every sibling and
                            // threw away the whole batch (the exact class the
                            // saveAgentGraph fix below was made for). One bad
                            // agent stays one bad agent.
                            runCatching {
                                // [T-subagent-nesting] spawnMany resolves the
                                // depth cap ONCE per batch (the spawner is
                                // fixed): workers spawned from a worker
                                // session get the delegation tools stripped,
                                // same rule as spawn().
                                val pair = buildGraphFor(
                                    context, spec.role, spec.task,
                                    mayDelegate(resolveRole(spec.role), spawnerRole),
                                )
                                    ?: return@runCatching "No custom agent '${spec.role.removePrefix("custom:")}' " +
                                        "in ${AgentFileStore.SANDBOX_DIR} (call list_agents)."
                                val (graph, node) = pair
                                // [T-spawn-crash] Save INSIDE the same guard:
                                // a failed saveAgentGraph (DB hiccup) used to
                                // fail the async, cancel every sibling in the
                                // batch and throw away ALL results.
                                app.providerRepository.saveAgentGraph(graph)
                                runSingle(
                                    context, app, graph, node, spec.role,
                                    TaskBoard.inject(spec.task, snapshot),
                                    teamId = spawnerSessionId,
                                    onStarted = { taskId ->
                                        onAgentStarted?.invoke(task.index, taskId)
                                    },
                                )
                            }.let { outcome ->
                                // [T-subagent-block-live] This agent is done
                                // with ITS line; say so now, not when the
                                // whole batch is through.
                                onAgentDone(
                                    task.index,
                                    spec.role,
                                    outcome.getOrElse { e ->
                                        "Subagent (${spec.role.lowercase()}) error: ${e.message}"
                                    },
                                    outcome.isSuccess,
                                )
                                outcome.getOrElse { e ->
                                    "Subagent (${spec.role.lowercase()}) error: ${e.message}"
                                }
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
        // [T-review-gate] The gate runs after the reviewer and is the last
        // word of the batch: it does not redo the work, it decides whether
        // the work may be called done and says what stands in the way.
        if (gate) {
            val gateVerdict = runExtra(
                context, app, AgentRole.FINAL_GATEKEEPER,
                "You are the last gate before this work is called done. Check the work below " +
                    "and the findings on the board: does it do what was asked, is every claim " +
                    "backed by the code that is really there, and what breaks. End with exactly " +
                    "one verdict on its own line - VERDICT: SHIP, VERDICT: SHIP_WITH_FIXES or " +
                    "VERDICT: DO_NOT_SHIP - and, for any verdict other than SHIP, the blockers " +
                    "as file and line, what is wrong and what has to change. A blocker you " +
                    "cannot point at in the code is not a blocker. Do not rewrite the work.\n\n" +
                    board.toString().take(9000),
            )
            sb.appendLine("### GATE (final_gatekeeper)")
            sb.appendLine(gateVerdict)
            sb.appendLine()
            board.append(TaskBoard.entry(0, "GATE", gateVerdict))
        }

        val boardPath = writeBoardFile(batchId, board.toString(), spawnerSessionId, context)
        if (boardPath != null) sb.appendLine("Board artifact: $boardPath")
        sb.appendLine("spawn_many finished.")
        // [event-matrix M2] The assembled result rides into ONE tool result —
        // uncapped, a 20-agent batch could assemble 100K+ chars (~25K
        // tokens) and blow the orchestrator's next-turn context. The FULL
        // text always lives in two durable places (BOARD.md artifact +
        // task_board tool); the returned text is a bounded view of it.
        val assembled = sb.toString()
        return if (assembled.length > RESULT_CAP_CHARS) {
            assembled.take(RESULT_CAP_CHARS) +
                "\n… (result truncated at $RESULT_CAP_CHARS chars — full per-agent results: " +
                "task_board tool${if (boardPath != null) " or $boardPath" else ""})"
        } else assembled
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

    /**
     * [T-task-board] Persist the final board as a workspace artifact.
     *
     * [deep-analysis fix] Previously resolved through the GLOBAL bind-mount
     * map — last-writer-wins across sessions: by the time a spawn_many batch
     * finishes, its workers have booted shells and the global
     * /var/minis/workspace entry points at the LAST worker's dir, so the
     * BOARD.md landed where the spawning chat could not see it (the same
     * class as the 'file disappears after first download' bug). Resolve
     * through the SESSION-scoped map for the spawner; debug callers without
     * a session fall back to the global resolution unchanged.
     */
    private fun writeBoardFile(batchId: String, board: String, sessionId: String?, context: Context): String? = try {
        val host = when {
            sessionId != null -> com.openminis.app.sandbox.PRootKernel
                .resolveSessionHostPath(sessionId, "/var/minis/workspace/$batchId/BOARD.md", context)
            else -> com.openminis.app.sandbox.PRootKernel
                .resolveHostPath("/var/minis/workspace/$batchId/BOARD.md")
        } ?: return null
        host.parentFile?.mkdirs()
        host.writeText(board)
        "/var/minis/workspace/$batchId/BOARD.md"
    } catch (_: Exception) {
        null
    }

    /**
     * [T-subagent-cancel-by-id] Cancels a background agent by its spawn id.
     *
     * Returns false when there is no such run, or when it has already
     * finished - cancelling a finished run is not an error, it simply has
     * nothing left to stop.
     */
    /**
     * [T-board-panel] Can this run still be stopped?
     *
     * A read-only answer about the state of the registry, not a way to stop
     * anything: the panel needs to know whether to offer a stop button at
     * all. A button that does nothing is worse than no button - the user
     * presses it, nothing happens, and they learn nothing about whether the
     * agent is still working.
     *
     * Today nothing is in the registry in production, so this answers false
     * for every task. That is the truth, and the panel shows it.
     */
    fun isCancellable(spawnId: String): Boolean {
        if (graphScopes.isCancellable(spawnId)) return true
        val entry = activeBackground[spawnId] ?: return false
        return entry.job.isActive
    }

    fun cancelBackground(spawnId: String): Boolean {
        if (graphScopes.cancel(spawnId)) return true
        val entry = activeBackground[spawnId] ?: return false
        // A job that is no longer active has already finished, or has already
        // been cancelled. Reporting that as "cancelled" would be a lie: there
        // was nothing left to stop. It also makes a second cancel of the
        // same agent honest - the first one stopped it, so the second one
        // finds it already stopped.
        if (!entry.job.isActive) return false
        entry.job.cancel()
        return true
    }

    /**
     * [T-agent-cancel-test] Registers a background entry for tests, so the
     * cancellation contract can be exercised without running a real agent.
     */
    internal fun registerBackgroundForTest(spawnId: String, job: kotlinx.coroutines.Job) {
        activeBackground[spawnId] = ActiveBackground("test", job)
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
         * [T-agent-card-live] The run's task id, the moment it is known.
         *
         * Same contract as runSingle's onStarted, but for the caller that
         * owns the chat line: the id is handed over before the run starts,
         * so the line can follow the agent's live progress.
         */
        onSpawned: ((taskId: String) -> Unit)? = null,
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
                spawnerSessionId, depsJson(dependsOn),
            )
        }
        val agentRole = resolveRole(role)
            ?: run {
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
        // [T-agent-card-live] Hand the id over before the run starts: the
        // chat's line needs it to find the live progress of THIS agent.
        onSpawned?.invoke(spawnId)
        return if (foreground) {
            if (spawnerRole != null) {
                // [T-soft-cancel] A child hired by an agent must survive the
                // parent's cancellation.
                //
                // The tokens of the child are already spent and its result is
                // still wanted, so the child runs in its OWN scope
                // (parentJob = null) while the parent waits for the outcome
                // through a deferred that the background callback completes.
                // If the parent is cancelled first, the parent stops waiting
                // and the child keeps working; the board records the real
                // outcome, so the work is not lost.
                //
                // The phone ceiling is enforced here too: launchBackground
                // releases the permit when the child ends, so it has to be
                // taken before the call.
                if (!backgroundLimiter.tryAcquire()) {
                    runCatching { app.providerRepository.deleteAgentGraph(graph.id) }
                    return "spawn refused: $MAX_BACKGROUND background subagents are already " +
                        "running (phone ceiling). Wait for their result notifications first, " +
                        "then spawn again."
                }
                val ready = kotlinx.coroutines.CompletableDeferred<String>()
                launchBackground(
                    context, app, graph, spawnId, role, task,
                    onBackgroundResult = { _, _, result -> ready.complete(result) },
                    teamId = spawnerSessionId, dependsOnJson = depsJson(dependsOn),
                    parentJob = null,
                )
                ready.await()
            } else {
                runSingle(context, app, graph, node, role, task, teamId = spawnerSessionId, dependsOnJson = depsJson(dependsOn))
            }
        } else {
            // [T-spawn-many] Phone ceiling: unbounded background spawns would
            // multiply live sessions, VMs and model calls on a device with
            // one radio and one battery.
            if (!backgroundLimiter.tryAcquire()) {
                runCatching { app.providerRepository.deleteAgentGraph(graph.id) }
                return "spawn refused: $MAX_BACKGROUND background subagents are already " +
                    "running (phone ceiling). Wait for their result notifications first, " +
                    "then spawn again."
            }
            return launchBackground(context, app, graph, spawnId, role, task, onBackgroundResult, spawnerSessionId, depsJson(dependsOn), parentJob = null)
        }
    }

    /**
     * Run a registered graph (builtin or custom) as a tool call.
     *
     * [T-task-board] Deep-analysis gap closed: run_graph runs were INVISIBLE
     * on the team board (only spawn_* recorded) — the cross-turn history lied
     * by omission about a third of the delegation surface. The run now lands
     * on the board like any spawn: RUNNING row before, terminal transition +
     * result after. teamId keeps the spawner's session (null = debug callers:
     * no team, no row — same rule as spawns).
     */
    suspend fun runGraph(
        context: Context,
        graphId: String,
        input: String,
        teamId: String? = null,
    ): String {
        val taskId = "graph-${UUID.randomUUID().toString().take(16)}"
        if (teamId != null) {
            AgentBoardRecorder.taskStarted(
                context, taskId = taskId, teamId = teamId,
                roleRequired = "graph:$graphId",
                title = input.lineSequence().firstOrNull().orEmpty(),
                description = input, workspaceDir = null,
            )
        }
        return try {
            graphScopes.run(taskId) {
                // [T-spawn-subagent-ephemeral] A graph invoked as a TOOL narrates
                // through the tool result and the live progress card in the
                // originating chat; a showcase session would be an invisible,
                // undeletable row (see AgentGraphRunner.run).
                val result = AgentGraphRunner.run(context, graphId, input, taskId = taskId, ephemeral = true)
                if (teamId != null) {
                    AgentBoardRecorder.taskFinished(
                        context, taskId = taskId, teamId = teamId,
                        assignedAgentId = null,
                        succeeded = result.status == RunStatus.SUCCESS,
                        result = formatResult(graphId, result),
                    )
                }
                formatResult(graphId, result)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // [T-board-cancelled-status] CancellationException is an
            // Exception in Kotlin, so the catch below would have taken it:
            // the run would have been recorded as a failure, and - worse -
            // the cancellation would have been swallowed, so the caller's
            // own cancellation would never have taken effect.
            if (teamId != null) {
                AgentBoardRecorder.taskFinished(
                    context, taskId = taskId, teamId = teamId,
                    assignedAgentId = null, succeeded = false,
                    result = "graph run cancelled",
                    cancelled = true,
                )
            }
            throw e
        } catch (e: Exception) {
            if (teamId != null) {
                AgentBoardRecorder.taskFinished(
                    context, taskId = taskId, teamId = teamId,
                    assignedAgentId = null, succeeded = false,
                    result = "graph run failed: ${e.message}",
                )
            }
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
        // [event-matrix M2] Handoff cap: a handoff is a STRUCTURED REPORT,
        // not the deliverable — one longer than 6K chars means the worker
        // pasted its artifact into the handoff against the format contract.
        // Without the cap, a 20-agent spawn_many assembled 100K+ chars of
        // handoffs into ONE tool result — a context blowout for the
        // orchestrator's very next turn.
        if (handoff.isNotEmpty()) {
            return if (handoff.length > HANDOFF_CAP) {
                handoff.take(HANDOFF_CAP) + "\n… (handoff truncated — full text on the board / BOARD.md)"
            } else handoff
        }
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
    internal fun promptForRole(role: AgentRole, task: String): String = when (role) {
        // [T-explore-role] The ZCode Explore port: a strictly read-only
        // researcher. The tool allowlist (defaultToolsForRole) enforces it
        // structurally — no shell, no writes, no delegation — so the prompt
        // describes the contract while the schema guarantees it.
        AgentRole.EXPLORE -> """
            You are a read-only research agent. Your ONE job:
            $task

            Gather evidence and report findings. You have NO write and NO
            execute tools by design: read code with file_read/grep/glob, read
            pages with webfetch/web_search, recall context with memory_get,
            inspect images with read_image. Do not ask for tools you do not
            have — work with what the schema gives you and stay precise.

            Format: a findings list, each with file:line (or URL) evidence
            and a one-line conclusion; close with a short DIRECT ANSWER
            section for the agent that spawned you.
        """.trimIndent()

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

        // [T-subagent-nesting] TEST_QUALITY_AUDITOR is in the delegation
        // matrix's reviewer set (mayDelegate) and carries spawn_subagent —
        // arming it without instructions was a gap: an agent that has a
        // tool it was never told how to use correctly either ignores it or
        // misuses it. The mandate is the reviewer pattern: ONE focused
        // verification, leaf agent, full context in the task.
        AgentRole.TEST_QUALITY_AUDITOR -> """
            You are a test quality auditor. Your ONE job:
            $task

            Audit the tests (or the plan) for:
            - Coverage gaps: behaviors with no test, edge cases unasserted
            - Weak assertions: tests that pass regardless of correctness
            - Setup/teardown that leaks state between tests

            Do NOT rewrite the tests unless the task says so. Audit and report.
            Format: numbered findings, each with the test (or gap) and the risk it hides.

            You may spawn_subagent for ONE focused runtime check when a finding
            needs execution proof you cannot get by reading (e.g.
            INDEPENDENT_TEST_DESIGNER to run the suspect test). Delegated agents
            are leaves — give them the complete question and expected outcome.
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

        // [T-role-prompts] These four roles had no prompt of their own and
        // fell through to the generic one, which told them nothing about the
        // job they were given. FINAL_GATEKEEPER in particular is the last
        // gate before work is called done, and it was the role a reviewer
        // would need in order to say something more specific than "looks
        // fine".
        AgentRole.FINAL_GATEKEEPER -> """
            You are the final gate before this work is called done. Your ONE job:
            $task

            The work has been implemented; now it has to survive a strict
            reading. Read the actual code, not the description of it.

            Check, in this order:
            1. Does the change do what the task asked - all of it, not a part
               of it?
            2. Is every claim in the result backed by the code that is
               really there?
            3. What breaks: empty input, a cancelled turn, a dependency that
               fails, a second run over the same file, the path nobody
               tested?
            4. Is anything left half-done, or done in a way that only looks
               right?

            End with an explicit verdict on its own line, exactly one of:
            VERDICT: SHIP
            VERDICT: SHIP_WITH_FIXES
            VERDICT: DO_NOT_SHIP
            and then, for any verdict other than SHIP, the exact blockers:
            file and line, what is wrong, what has to change. A blocker you
            cannot point at in the code is not a blocker - say so plainly
            instead of inventing one.

            Do not rewrite the work and do not fix it yourself. Your
            deliverable is the verdict and the blockers, nothing else.
        """.trimIndent()

        AgentRole.INDEPENDENT_TEST_DESIGNER -> """
            You are a test design agent. Your ONE job:
            $task

            Design the tests that would catch this work being wrong. Do not
            write the implementation and do not review the code's style.

            For every behaviour that matters, state:
            - the case, as a concrete input and a concrete expected result;
            - the boundary it sits on: empty, one, many, the largest value,
              the value that must fail;
            - the failure it protects against - a real one, not a generic
              "should not crash";
            - how to run it.

            Cover the paths nobody checks: the cancelled one, the concurrent
            one, the one where a dependency is missing, the one that runs
            twice over the same file. Say which of your cases would fail
            against the current code, and why. If a behaviour cannot be
            tested as it is written, say that and say what would have to
            change to make it testable.
        """.trimIndent()

        AgentRole.DEPENDENCY_GUARDIAN -> """
            You are a dependency guardian agent. Your ONE job:
            $task

            Check what this work depends on and what it would break.

            For every dependency - a library, a platform API, a service, a
            file format, another module - state:
            - which version or contract the work relies on, and whether that
              is what the dependency actually provides;
            - what change in the dependency would break the work, and what
              the work would break in the dependency;
            - the licences, the size, the permissions and the transitive
              dependencies that come with it;
            - whether the same job could be done with what the project
              already has.

            Rate each finding high, medium or low, and say plainly when the
            answer is "no risk here". Do not manufacture a concern to look
            thorough.
        """.trimIndent()

        AgentRole.DOCUMENTATION_AGENT -> """
            You are a documentation agent. Your ONE job:
            $task

            Write the documentation this work needs, for the person who comes
            next.

            Cover, in this order:
            - what the thing does and when to use it, in two sentences;
            - how to run it, as commands that can be copied;
            - the inputs, the outputs and every option, with its default;
            - the failure modes: what each error means and what to do about
              it;
            - one example of the normal case and one of an edge case.

            Describe only what the code really does. If you had to guess how
            something behaves, mark it unverified instead of writing it as
            fact. Keep the style and the wording of the file you are editing
            and change nothing else in it.
        """.trimIndent()

        else -> """
            You are a ${role.name.lowercase().replace('_', ' ')} agent. Your ONE job:
            $task

            Focus exclusively on this task. Report your findings clearly.
            Do not do another role's work.
        """.trimIndent()
    }

    /** Role → default tool surface. Lives in [SubagentRoles] so the matrix
     *  is testable without instantiating the executor. */
    private fun defaultToolsForRole(role: AgentRole): List<String> =
        SubagentRoles.defaultToolsForRole(role)

    /** [event-matrix G12] Lenient role resolution: models frequently
     * lowercase enum names ("senior_implementer"); valueOf() rejects them
     * and the whole spawn fails on a casing typo. Normalize: uppercase +
     * spaces/hyphens → underscores, before the strict lookup. */
    private fun resolveRole(role: String): AgentRole? {
        runCatching { AgentRole.valueOf(role) }.getOrNull()?.let { return it }
        val normalized = role.trim().uppercase().replace(' ', '_').replace('-', '_')
        return runCatching { AgentRole.valueOf(normalized) }.getOrNull()
    }

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
    private fun mayDelegate(childRole: AgentRole?, spawnerRole: AgentRole?): Boolean =
        SubagentRoles.mayDelegate(childRole, spawnerRole)

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
        // [T-explore-role] Research reads run on the light tier — the
        // explore child is a bulk reader, not a decision maker.
        AgentRole.EXPLORE -> "analyst"
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

    /**
     * [T-subagent-nesting] The delegation capability matrix.
     *
     * MAIN chat (spawnerRole == null) may arm the ORCHESTRATOR (the entry
     * delegator) and the reviewer roles. The ORCHESTRATOR may arm worker
     * roles and reviewer roles - its tool surface has always granted
     * spawn_subagent/spawn_many/cancel_subagent/task_board, and the
     * orchestrator prompt tells it to split the task and hire workers, but
     * the matrix answered "reviewers only", so the tool was there and the
     * permission was not.
     *
     * The structural caps stay: an orchestrator cannot arm another
     * orchestrator, a reviewer cannot arm a reviewer, a worker arms
     * nobody. The workers a delegator may arm are leaves, so no chain can
     * pass depth 3 and uncontrolled fan-out stays impossible.
     */
    internal fun mayDelegate(childRole: AgentRole?, spawnerRole: AgentRole?): Boolean {
        val reviewers = setOf(
            AgentRole.CODE_CORRECTNESS_REVIEWER,
            AgentRole.SECURITY_REVIEWER,
            AgentRole.PERFORMANCE_REVIEWER,
            AgentRole.TEST_QUALITY_AUDITOR,
        )
        // The roles that cannot delegate: they are leaves, so a delegator
        // that arms them stays inside the depth cap.
        val workers = setOf(
            AgentRole.REQUIREMENTS_ANALYST,
            AgentRole.CODEBASE_DISCOVERY,
            AgentRole.SOLUTION_ARCHITECT,
            AgentRole.INDEPENDENT_TEST_DESIGNER,
            AgentRole.SENIOR_IMPLEMENTER,
            AgentRole.DEPENDENCY_GUARDIAN,
            AgentRole.FINAL_GATEKEEPER,
            AgentRole.DOCUMENTATION_AGENT,
            AgentRole.EXPLORE,
        )
        return when (spawnerRole) {
            // Event-matrix fix (A8 x A3): a custom agent (childRole == null)
            // spawned from MAIN may delegate - spawnCustom() already grants
            // exactly that (spawnerRole == null); the batch path disagreed
            // with the single path for the same user action. Both entry
            // points now answer identically.
            null -> childRole == null || childRole == AgentRole.ORCHESTRATOR || childRole in reviewers
            AgentRole.ORCHESTRATOR ->
                childRole != null &&
                    childRole != AgentRole.ORCHESTRATOR &&
                    (childRole in reviewers || childRole in workers)
            else -> false
        }
    }
    val SPAWNABLE = listOf(
        // [T-explore-role] First in the list: research-first agents spawn
        // explorers more often than any specialist.
        "EXPLORE",
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

    /**
     * Scoped default tools per role (OpenAI Agents SDK pattern). A testable
     * pure function on purpose — the executor delegates here.
     */
    fun defaultToolsForRole(role: AgentRole): List<String> = when (role) {
        // [T-explore-role] Read-only by construction: no shell (grep/glob
        // cover search natively), no write tools, no delegation. A research
        // child of a PLAN or EDIT parent cannot write around the mode — the
        // allowlist IS the enforcement, not a prompt suggestion.
        AgentRole.EXPLORE ->
            listOf(
                "file_read", "read_image", "grep", "glob",
                "web_search", "webfetch", "memory_get", "todo_read",
            )
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
            listOf("shell_execute", "file_read", "spawn_subagent", "spawn_many", "cancel_subagent", "task_board")
        AgentRole.CODE_CORRECTNESS_REVIEWER,
        AgentRole.SECURITY_REVIEWER,
        AgentRole.PERFORMANCE_REVIEWER,
        AgentRole.TEST_QUALITY_AUDITOR ->
            listOf("shell_execute", "file_read", "browser_use", "spawn_subagent", "cancel_subagent")
        else ->
            listOf("shell_execute", "file_read", "browser_use")
    }
}
