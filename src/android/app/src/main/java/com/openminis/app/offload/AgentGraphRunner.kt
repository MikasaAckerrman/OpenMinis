package com.openminis.app.offload

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.AgentGraph
import com.openminis.app.data.model.AgentNode
import com.openminis.app.data.model.AgentRole
import com.openminis.app.data.model.EdgeType
import com.openminis.app.data.model.GraphRunResult
import com.openminis.app.data.model.Handoff
import com.openminis.app.data.model.HandoffStatus
import com.openminis.app.data.model.RunStatus
import com.openminis.app.data.model.TraceEvent
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Core execution engine for agent graphs.
 * Runs a graph of agents with structured handoffs, artifact persistence, and tracing.
 */
internal object AgentGraphRunner {

    private const val LOG_TAG = "AgentGraph"
    private const val ARTIFACT_DIR_BASE = "/var/minis/workspace"
    private const val TRACE_DIR = "/var/minis/offloads"

    /** In-memory state for a running graph. */
    data class GraphState(
        val graph: AgentGraph,
        val taskId: String,
        val artifactDir: String,
        val input: String,
        val sessionMap: MutableMap<String, String> = mutableMapOf(), // runtimeId -> sessionId
        /**
         * [T-agent-graph-role-session] runtime-id -> sessionId is not enough when
         * a plan is split into sequential steps for the SAME role: step 2 should
         * remember what step 1 did. Nodes that opt in via `sessionGroup` share a
         * session keyed here, so their history accumulates while OTHER roles stay
         * isolated — the test designer still never sees the implementation.
         */
        val sessionByGroup: MutableMap<String, String> = mutableMapOf(),
        /** The single user-visible session narrating this run, or null. */
        var showcaseId: String? = null,
        val handoffMap: MutableMap<String, Handoff> = mutableMapOf(), // nodeId -> handoff received
        val artifactIndex: MutableMap<String, String> = mutableMapOf(), // path -> content
        val trace: MutableList<TraceEvent> = mutableListOf(),
        val nodeStatus: MutableMap<String, NodeStatus> = mutableMapOf(),
        /** Nodes already enqueued or started — guards against double-dispatch
         *  when a fan-in node's predecessors finish at different times. */
        val dispatched: MutableSet<String> = mutableSetOf(),
        /** nodeId -> why the scope guard rejected its handoff. */
        val scopeViolations: MutableMap<String, String> = mutableMapOf(),
    )

    enum class NodeStatus { PENDING, RUNNING, COMPLETED, FAILED, BLOCKED, SKIPPED, OUT_OF_SCOPE }

    data class ExecutionContext(
        val state: GraphState,
        val context: Context,
        val providerRepo: ProviderRepository,
        val json: Json,
    )

    /**
     * Execute a graph with initial input.
     */
    suspend fun run(
        context: Context,
        graphId: String,
        input: String,
        taskId: String = UUID.randomUUID().toString(),
    ): GraphRunResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext as MinisApp
        val providerRepo = app.providerRepository
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        com.openminis.app.logging.AppLogger.info(
            LOG_TAG,
            "[${taskId.take(8)}] RUN START graph=$graphId input=${input.take(200)}",
        )

        // Load graph
        val graph = providerRepo.loadAgentGraph(graphId)
            ?: run {
                com.openminis.app.logging.AppLogger.error(
                    LOG_TAG,
                    "[${taskId.take(8)}] graph '$graphId' not found. " +
                        "Available: ${providerRepo.listAgentGraphs().joinToString { it.id }}",
                )
                return@withContext GraphRunResult(
                    taskId = taskId,
                    status = RunStatus.FAILED,
                    error = "Graph not found: $graphId",
                )
            }

        // Validate graph
        val errors = graph.validate()
        if (errors.isNotEmpty()) {
            com.openminis.app.logging.AppLogger.error(
                LOG_TAG,
                "[${taskId.take(8)}] graph '$graphId' invalid: ${errors.joinToString("; ")}",
            )
            return@withContext GraphRunResult(
                taskId = taskId,
                status = RunStatus.FAILED,
                error = "Graph validation failed: ${errors.joinToString(", ")}",
            )
        }

        val runtimeCount = graph.nodes.sumOf { it.replicas }
        com.openminis.app.logging.AppLogger.info(
            LOG_TAG,
            "[${taskId.take(8)}] graph '${graph.name}': ${graph.nodes.size} config nodes -> " +
                "$runtimeCount runtime, ${graph.edges.size} edges, " +
                "maxParallel=${graph.config.maxParallelNodes}, " +
                "contextBudget=${graph.config.contextBudgetTokens}",
        )

        // Prepare artifact directory
        val artifactDir = "$ARTIFACT_DIR_BASE/$taskId"
        File(artifactDir).mkdirs()

        // Initialize state
        val state = GraphState(
            graph = graph,
            taskId = taskId,
            artifactDir = artifactDir,
            input = input,
        )

        // Initialize all nodes as PENDING
        for (node in graph.nodes) {
            // Replicated nodes get one status slot per replica.
            for (rid in node.replicaIds()) {
                state.nodeStatus[rid] = NodeStatus.PENDING
            }
        }

        // [T-agent-graph-showcase] One readable session narrating the run. Worker
        // sessions stay hidden; this is what the user opens. Created before the
        // first node so the very first "started" line has somewhere to land.
        state.showcaseId = AgentRunShowcase.create(
            context = context,
            taskId = taskId,
            graphName = graph.name,
            input = input,
            nodeCount = graph.nodes.size,
            runtimeCount = runtimeCount,
        )

        val execContext = ExecutionContext(state, context, providerRepo, json)

        // Execute graph
        val result = executeGraph(execContext)

        AgentRunShowcase.noteFinish(
            context = context,
            showcaseId = state.showcaseId,
            status = result.status.name,
            nodeStatuses = state.nodeStatus.mapValues { it.value.name },
            artifactDir = artifactDir,
            artifactCount = result.artifacts.size,
            scopeViolations = state.scopeViolations,
        )

        // Tool policies are keyed by session id in a process-wide map. Without
        // this the map grows by one entry per node per run and never shrinks —
        // small, but a leak that also means a deleted session's policy lingers
        // and could apply to a recycled id.
        for (sid in state.sessionMap.values + state.sessionByGroup.values) {
            com.openminis.app.tools.AgentToolPolicyStore.clearPolicy(sid)
        }

        // Write trace
        if (graph.config.enableTracing) {
            writeTrace(taskId, result.trace, json)
        }

        // Write final artifacts summary
        writeArtifactIndex(artifactDir, result.artifacts)

        // A per-node status summary is the single most useful line when a run
        // did not do what was expected: it shows at a glance which stage was
        // skipped, blocked, or never reached.
        val summary = state.nodeStatus.entries
            .sortedBy { it.key }
            .joinToString(", ") { "${it.key}=${it.value}" }
        com.openminis.app.logging.AppLogger.info(
            LOG_TAG,
            "[${taskId.take(8)}] RUN END status=${result.status} " +
                "artifacts=${result.artifacts.size} traceEvents=${result.trace.size}\n" +
                "  nodes: $summary\n" +
                "  artifacts dir: $artifactDir\n" +
                "  trace: $TRACE_DIR/agent_graph_$taskId.json",
        )
        if (state.scopeViolations.isNotEmpty()) {
            com.openminis.app.logging.AppLogger.error(
                LOG_TAG,
                "[${taskId.take(8)}] SCOPE VIOLATIONS: " +
                    state.scopeViolations.entries.joinToString("; ") { "${it.key}: ${it.value}" },
            )
        }

        result
    }

    /** Main graph execution loop. */
    /**
     * [T-agent-graph-parallel] Runtime ids differ from config ids when a node
     * has replicas: `implementer` with replicas=2 runs as `implementer#1` and
     * `implementer#2`. Everything downstream (status, sessions, handoffs) keys
     * off the RUNTIME id so the two never share state; this resolves back to
     * the config node plus the replica's 0-based index for sharding.
     */
    private fun resolveRuntimeNode(
        graph: AgentGraph,
        runtimeId: String,
    ): Pair<AgentNode, Int>? {
        val hash = runtimeId.lastIndexOf('#')
        if (hash < 0) {
            val node = graph.nodes.find { it.id == runtimeId } ?: return null
            return node to 0
        }
        val baseId = runtimeId.substring(0, hash)
        val idx = runtimeId.substring(hash + 1).toIntOrNull() ?: return null
        val node = graph.nodes.find { it.id == baseId } ?: return null
        return node to (idx - 1)
    }

    private suspend fun executeGraph(execContext: ExecutionContext): GraphRunResult {
        val state = execContext.state
        val graph = state.graph

        // Queue of ready node IDs (runtime ids — see resolveRuntimeNode)
        val readyQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val entry = graph.nodes.find { it.id == graph.entryNodeId }
        // Entry may itself be replicated; enqueue every replica.
        readyQueue.addAll(entry?.replicaIds() ?: listOf(graph.entryNodeId))

        // Track running nodes for parallelism limit
        val runningJobs = mutableMapOf<String, Job>()

        while (true) {
            // Exit when every declared exit node reached a terminal state and
            // nothing is left to run. `all {}` on an empty list is true, so a
            // graph without exitNodeIds ends as soon as the queue drains —
            // which is the desired behaviour (it just runs to exhaustion).
            val exitRuntimeIds = graph.exitNodeIds.flatMap { cid ->
                graph.nodes.find { it.id == cid }?.replicaIds() ?: listOf(cid)
            }
            val exitsSettled = exitRuntimeIds.all { id ->
                when (state.nodeStatus[id]) {
                    NodeStatus.COMPLETED, NodeStatus.FAILED,
                    NodeStatus.BLOCKED, NodeStatus.SKIPPED,
                    NodeStatus.OUT_OF_SCOPE -> true
                    else -> false
                }
            }
            if (exitsSettled && readyQueue.isEmpty() && runningJobs.isEmpty()) {
                break
            }

            // Nothing ready but work in flight → wait for a job to land.
            if (readyQueue.isEmpty() && runningJobs.isNotEmpty()) {
                kotlinx.coroutines.delay(100)
                continue
            }

            // Nothing ready, nothing running, exits unsettled → stuck.
            if (readyQueue.isEmpty() && runningJobs.isEmpty()) {
                val incompleteExits = exitRuntimeIds.filter { state.nodeStatus[it] != NodeStatus.COMPLETED }
                return GraphRunResult(
                    taskId = state.taskId,
                    status = RunStatus.FAILED,
                    artifacts = state.artifactIndex,
                    trace = state.trace,
                    error = "Deadlock: incomplete exit nodes ${incompleteExits.joinToString(", ")}",
                )
            }

            // Start next ready node (respect maxParallelNodes)
            while (readyQueue.isNotEmpty() && runningJobs.size < graph.config.maxParallelNodes) {
                val nodeId = readyQueue.poll() ?: break
                val (node, replicaIndex) = resolveRuntimeNode(graph, nodeId) ?: continue
                if (state.nodeStatus[nodeId] != NodeStatus.PENDING) continue
                // Guard against double-dispatch: a fan-in node can be queued by
                // several predecessors. `dispatched` is the single source of truth
                // for "already launched".
                if (!state.dispatched.add(nodeId)) continue

                val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    executeNode(execContext, node, nodeId, replicaIndex)
                }
                runningJobs[nodeId] = job
            }

            // Check completed jobs
            val toRemove = mutableListOf<String>()
            for ((nodeId, job) in runningJobs) {
                if (job.isCompleted) {
                    toRemove.add(nodeId)
                }
            }
            for (nodeId in toRemove) {
                runningJobs.remove(nodeId)
                // Queue successors that just became runnable
                for (readyId in queueSuccessors(execContext, nodeId)) {
                    if (readyId !in state.dispatched) readyQueue.add(readyId)
                }
            }

            // Small delay to prevent busy loop
            kotlinx.coroutines.delay(50)
        }

        // Determine final status
        val allExitRuntimeIds = graph.exitNodeIds.flatMap { cid ->
            graph.nodes.find { it.id == cid }?.replicaIds() ?: listOf(cid)
        }
        // A scope violation anywhere means the chain of custody broke — report
        // ESCALATED rather than SUCCESS even if the exit nodes finished.
        val finalStatus = if (state.scopeViolations.isNotEmpty()) {
            RunStatus.ESCALATED
        } else if (allExitRuntimeIds.all { state.nodeStatus[it] == NodeStatus.COMPLETED }) {
            RunStatus.SUCCESS
        } else if (allExitRuntimeIds.any { state.nodeStatus[it] == NodeStatus.COMPLETED }) {
            RunStatus.PARTIAL
        } else {
            RunStatus.FAILED
        }

        return GraphRunResult(
            taskId = state.taskId,
            status = finalStatus,
            artifacts = state.artifactIndex,
            trace = state.trace,
        )
    }

    /** Execute a single agent node. */
    private suspend fun executeNode(
        execContext: ExecutionContext,
        node: AgentNode,
        runtimeId: String = node.id,
        replicaIndex: Int = 0,
    ) {
        val state = execContext.state
        val graph = state.graph
        val context = execContext.context
        val providerRepo = execContext.providerRepo

        state.nodeStatus[runtimeId] = NodeStatus.RUNNING
        addTrace(
            state, runtimeId, node.role, "START",
            if (node.replicas > 1) "replica ${replicaIndex + 1}/${node.replicas} started"
            else "Node execution started",
        )

        // Resolve the model. An explicit modelEntryId wins; otherwise the role
        // goes through the fallback chain (per-role key -> shared default ->
        // whatever the user already uses), and HOW it resolved is traced so a
        // surprising model choice is diagnosable rather than mysterious.
        val modelEntryId = if (node.modelEntryId.isNotBlank()) {
            addTrace(state, runtimeId, node.role, "MODEL", "explicit modelEntryId=${node.modelEntryId}")
            node.modelEntryId
        } else if (node.modelRole.isNotBlank()) {
            val resolved = providerRepo.resolveModelEntryForRole(node.modelRole) { line ->
                addTrace(state, runtimeId, node.role, "MODEL", line)
            }
            resolved?.id ?: run {
                handleMissingModelEntry(
                    execContext, node, runtimeId,
                    "modelRole '${node.modelRole}' resolved to nothing — no provider configured?",
                )
                return
            }
        } else {
            handleMissingModelEntry(
                execContext, node, runtimeId,
                "node declares neither modelEntryId nor modelRole",
            )
            return
        }

        // Get or create session for this node.
        // NOTE: not `getOrPut { … }` — the initializer lambda is NOT an inline
        // suspend context, so calling a suspend fun inside it fails to compile.
        //
        // [T-agent-graph] node.allowedTools is handed to the session manager so
        // the tool schema is already restricted on the FIRST turn. This is a
        // real barrier, not advice in the prompt: a tool absent from the schema
        // cannot be called at all.
        // [T-agent-graph-role-session] When the node declares a sessionGroup,
        // every node in that group shares one session and therefore one history
        // — that is how step 3 of a staged plan knows what steps 1-2 wrote.
        // Otherwise the session is per runtime id, keeping replicas and roles
        // isolated from each other.
        val sessionKey = node.sessionGroup.ifBlank { runtimeId }
        val sessionStore = if (node.sessionGroup.isBlank()) state.sessionMap else state.sessionByGroup
        val existingSession = sessionStore[sessionKey]
        val sessionId = existingSession ?: AgentSessionManager.createAndBindSession(
            context = context,
            modelEntryId = modelEntryId,
            allowedTools = node.allowedTools,
            agentRunId = state.taskId,
            agentRole = node.role.name,
        ).also { sessionStore[sessionKey] = it }
        if (existingSession != null && node.sessionGroup.isNotBlank()) {
            addTrace(
                state, runtimeId, node.role, "SESSION",
                "reusing session of group '${node.sessionGroup}' — earlier steps are in its history",
            )
        }

        AgentRunShowcase.noteStart(
            context = context,
            showcaseId = state.showcaseId,
            role = node.role,
            runtimeId = runtimeId,
            replicaInfo = if (node.replicas > 1) "${replicaIndex + 1}/${node.replicas}" else null,
            model = modelEntryId.take(24),
        )

        // Build system prompt with role + tool allowlist + scope contract
        val systemPrompt = buildSystemPrompt(node, state, replicaIndex)

        // Build user message with handoff from predecessors + input
        val userMessage = buildUserMessage(node, state, runtimeId)

        // Send to model
        var response: String? = null
        var attempts = 0
        val maxAttempts = graph.config.retryPolicy.maxRetries + 1

        while (attempts < maxAttempts && response == null) {
            attempts++
            addTrace(state, runtimeId, node.role, "ATTEMPT", "Attempt $attempts/$maxAttempts")

            val promptResult = AgentSessionManager.sendAndWait(
                context = context,
                sessionId = sessionId,
                text = userMessage,
                thinkingLevel = node.thinkingLevel,
                timeoutMs = graph.config.defaultTimeoutMs,
            )

            if (promptResult.status == "Completed" && promptResult.responseText != null) {
                response = promptResult.responseText
            } else if (graph.config.retryPolicy.retryOn.contains(promptResult.status)) {
                addTrace(state, runtimeId, node.role, "RETRY", "Status: ${promptResult.status}, waiting ${graph.config.retryPolicy.backoffMs}ms")
                kotlinx.coroutines.delay(graph.config.retryPolicy.backoffMs)
            } else {
                // Non-retryable error
                break
            }
        }

        // `response` is a nullable var mutated in the loop above; copy into a
        // local val so the compiler can smart-cast it to non-null below.
        var finalResponse: String = response
            ?: run {
                state.nodeStatus[runtimeId] = NodeStatus.FAILED
                addTrace(state, runtimeId, node.role, "FAILED", "All attempts exhausted")
                AgentRunShowcase.noteFailure(
                    context, state.showcaseId, node.role,
                    "no valid reply after $maxAttempts attempt(s)",
                )
                return
            }

        // Validate handoff
        var validation = HandoffValidator.validateResponse(finalResponse)
        if (!validation.isValid) {
            addTrace(state, runtimeId, node.role, "INVALID_HANDOFF", validation.message)
            // One corrective round-trip: tell the model exactly what was wrong.
            val retryMessage =
                "Your previous response was invalid: ${validation.message}\n\n" +
                "Please respond with a valid handoff block."
            val retryResult = AgentSessionManager.sendAndWait(
                context = context,
                sessionId = sessionId,
                text = retryMessage,
                thinkingLevel = node.thinkingLevel,
                timeoutMs = graph.config.defaultTimeoutMs,
            )
            val retryText = retryResult.responseText
            if (retryResult.status == "Completed" && retryText != null) {
                val retryValidation = HandoffValidator.validateResponse(retryText)
                if (retryValidation.isValid) {
                    finalResponse = retryText
                    validation = retryValidation
                }
            }
        }

        val handoff = validation.handoff
            ?: HandoffValidator.parseHandoff(finalResponse)
            ?: run {
                handleParseFailure(execContext, node, finalResponse)
                return
            }

        // [T-agent-graph-scope] Scope guard. The prompt tells the node what it
        // owns; this verifies the instruction held. A node that produced a
        // neighbour's artifact is stopped here rather than poisoning the chain
        // with work that bypassed the agent meant to do it.
        when (val verdict = ScopeGuard.check(node, handoff, finalResponse)) {
            is ScopeGuard.Verdict.OutOfScope -> {
                addTrace(state, runtimeId, node.role, "OUT_OF_SCOPE", verdict.reason)
                AgentRunShowcase.noteOutOfScope(
                    context, state.showcaseId, node.role, verdict.reason,
                )
                state.nodeStatus[runtimeId] = NodeStatus.OUT_OF_SCOPE
                // Keep the handoff for the trace, but do NOT let successors
                // consume it — an out-of-scope artifact is not a deliverable.
                state.scopeViolations[runtimeId] = verdict.reason
                return
            }
            is ScopeGuard.Verdict.Suspicious -> {
                addTrace(state, runtimeId, node.role, "SCOPE_WARNING", verdict.reason)
            }
            ScopeGuard.Verdict.Ok -> Unit
        }

        // Store handoff
        state.handoffMap[runtimeId] = handoff
        state.nodeStatus[runtimeId] = when (handoff.status) {
            HandoffStatus.COMPLETE -> NodeStatus.COMPLETED
            HandoffStatus.BLOCKED -> NodeStatus.BLOCKED
            HandoffStatus.NEEDS_CLARIFICATION -> NodeStatus.BLOCKED
        }

        // Extract and persist artifacts
        val artifactPaths = HandoffValidator.extractArtifactPaths(handoff.deliverables)
        for (path in artifactPaths) {
            val content = readArtifactContent(context, path)
            if (content != null) {
                state.artifactIndex[path] = content
                // Also copy to task artifact dir
                val targetPath = "${state.artifactDir}/${File(path).name}"
                File(targetPath).writeText(content)
            }
        }

        // If COMPLETE with deliverables, also save the handoff itself
        if (handoff.status == HandoffStatus.COMPLETE) {
            val handoffPath = "${state.artifactDir}/${runtimeId}_handoff.md"
            File(handoffPath).writeText(HandoffValidator.buildHandoff(handoff))
            state.artifactIndex[handoffPath] = HandoffValidator.buildHandoff(handoff)
        }

        addTrace(state, runtimeId, node.role, "HANDOFF", "Status: ${handoff.status}, To: ${handoff.to}")
        AgentRunShowcase.noteHandoff(
            context = context,
            showcaseId = state.showcaseId,
            role = node.role,
            runtimeId = runtimeId,
            handoff = handoff,
        )
    }

    /** Build system prompt for an agent node. */
    private fun buildSystemPrompt(
        node: AgentNode,
        state: GraphState,
        replicaIndex: Int = 0,
    ): String {
        val roleName = node.role.name.replace("_", " ")
        val toolList = ToolAllowlistEnforcer.formatAllowlist(node)
        val scope = ScopeGuard.scopeContract(node, replicaIndex)
        val delegates = if (node.mayDelegateTo.isEmpty()) {
            "Set TO: to whichever role the work must reach next."
        } else {
            "TO: must be one of ${node.mayDelegateTo.joinToString(", ") { it.name }}."
        }

        return """
            You are the $roleName agent in a multi-agent coding pipeline.
            You are NOT a general assistant. You do one job and hand off.

            $scope

            $toolList
            A tool absent from your schema is not merely discouraged — it is
            unavailable. If you need something outside your tools, that is a
            signal the work belongs to another agent: hand off, do not improvise.

            YOUR INSTRUCTIONS:
            ${node.systemPrompt}

            BEFORE YOU ANSWER, verify all three:
              1. Is the artifact I am about to produce the one MY role owns?
                 If no — stop and hand off with STATUS: BLOCKED.
              2. Am I about to do work that belongs to a later agent because it
                 seemed faster? If yes — stop. Speed is not the goal; an
                 independent chain of custody is.
              3. Did I receive everything I need? If no — do NOT guess or
                 invent it. Return STATUS: NEEDS_CLARIFICATION and say exactly
                 what is missing.

            HANDOFF PROTOCOL — mandatory, no prose after the block:
            === HANDOFF START ===
            FROM: ${node.role.name}
            TO: [role]
            TASK_ID: ${state.taskId}
            STATUS: COMPLETE | BLOCKED | NEEDS_CLARIFICATION
            DELIVERABLES:
            - [concrete artifacts you produced — file paths where applicable]
            SUCCESS_CRITERIA_MET:
            - [what you actually verified, not what you assume]
            REMAINING_RISKS_OR_OPEN_QUESTIONS:
            - [anything you could not confirm]
            NEXT_REQUIRED_ACTION:
            [one sentence: what the receiving agent must do]
            === HANDOFF END ===

            FROM: must read exactly ${node.role.name}. $delegates
            STATUS: COMPLETE requires a non-empty DELIVERABLES list.
            A handoff that misstates FROM, or ships an artifact this role does
            not own, is rejected by the engine and the run stops. The scope
            contract is enforced in code, not on trust.
        """.trimIndent()
    }

    /** Build user message with context from predecessors. */
    private fun buildUserMessage(
        node: AgentNode,
        state: GraphState,
        runtimeId: String = node.id,
    ): String {
        val sb = StringBuilder()
        val graph = state.graph

        sb.appendLine("ORIGINAL TASK: ${state.input}")
        sb.appendLine()

        if (node.id == graph.entryNodeId) {
            sb.appendLine("You are the first agent in this run. Begin your part.")
        } else {
            // Predecessors are CONFIG ids; a replicated predecessor contributes
            // one handoff per replica, so expand before looking them up.
            val predIds = graph.edges
                .filter { it.to == node.id }
                .flatMap { edge ->
                    graph.nodes.find { it.id == edge.from }?.replicaIds()
                        ?: listOf(edge.from)
                }
                .distinct()

            // [T-agent-graph-memory] Direct input verbatim, older steps as a
            // digest under a per-role budget. Pasting every upstream handoff in
            // full would make token cost grow quadratically with pipeline
            // length for text most nodes never refer to.
            val budget = ContextBudget.budgetFor(node.role, graph.config.contextBudgetTokens)
            val older = state.handoffMap.entries
                .filter { it.key !in predIds }
                .map { it.key to it.value }
            val upstream = ContextBudget.buildUpstreamContext(
                directPredecessorIds = predIds,
                handoffs = state.handoffMap,
                olderHandoffs = older,
                budgetTokens = budget,
            )
            sb.append(upstream)
            if (upstream.isNotEmpty()) sb.appendLine()
            val found = predIds.count { state.handoffMap.containsKey(it) }

            // Surface predecessors that were stopped, so this node does not
            // silently assume their work exists.
            val blocked = predIds.filter { pid ->
                state.nodeStatus[pid] == NodeStatus.OUT_OF_SCOPE ||
                    state.nodeStatus[pid] == NodeStatus.FAILED
            }
            if (blocked.isNotEmpty()) {
                sb.appendLine("--- UPSTREAM PROBLEMS ---")
                for (pid in blocked) {
                    val why = state.scopeViolations[pid] ?: "node failed"
                    sb.appendLine("$pid: ${state.nodeStatus[pid]} — $why")
                }
                sb.appendLine(
                    "Do NOT compensate by doing their work. If their artifact is " +
                        "required for yours, return STATUS: BLOCKED."
                )
                sb.appendLine()
            }

            if (found == 0) {
                sb.appendLine(
                    "No predecessor handoff reached you. Do NOT invent the missing " +
                        "input — return STATUS: NEEDS_CLARIFICATION naming what is absent."
                )
                sb.appendLine()
            }
        }

        // Sibling awareness for replicas: knowing the split exists is what
        // stops two coders from writing the same file.
        if (node.replicas > 1) {
            val siblings = node.replicaIds().filter { it != runtimeId }
            sb.appendLine("--- PARALLEL SIBLINGS ---")
            sb.appendLine(
                "You run alongside ${siblings.size} sibling(s): ${siblings.joinToString(", ")}. " +
                    "Each owns a different shard. Touch only yours."
            )
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * Advance the graph after [completedNodeId] finished. Returns the ids of
     * nodes that became runnable (all their incoming edges resolved).
     *
     * Fan-in rule: a node runs only when EVERY predecessor has reached a
     * terminal state, and at least one of them COMPLETED. That is what makes
     * the parallel review block (4 reviewers -> auditor) work: the auditor
     * waits for all four instead of starting on the first one.
     */
    private suspend fun queueSuccessors(execContext: ExecutionContext, completedRuntimeId: String): List<String> {
        val state = execContext.state
        val graph = state.graph
        val ready = mutableListOf<String>()

        fun terminal(id: String): Boolean = when (state.nodeStatus[id]) {
            NodeStatus.COMPLETED, NodeStatus.FAILED,
            NodeStatus.BLOCKED, NodeStatus.SKIPPED,
            NodeStatus.OUT_OF_SCOPE -> true
            else -> false
        }

        /** Runtime ids a config node expands to. */
        fun runtimeIdsOf(configId: String): List<String> =
            graph.nodes.find { it.id == configId }?.replicaIds() ?: listOf(configId)

        // Edges are declared between CONFIG ids; map the finished replica back.
        val completedConfigId = resolveRuntimeNode(graph, completedRuntimeId)?.first?.id
            ?: completedRuntimeId

        for (edge in graph.edges.filter { it.from == completedConfigId }) {
            val incoming = graph.edges.filter { it.to == edge.to }

            // Fan-in over REPLICAS too: a node waits for every replica of every
            // predecessor. Without this the auditor would start after one of
            // two implementer replicas, reviewing half the work.
            val allPredRuntimeIds = incoming.flatMap { runtimeIdsOf(it.from) }.distinct()
            if (!allPredRuntimeIds.all { terminal(it) }) continue

            // At least one predecessor must have COMPLETED, and CONDITIONAL
            // edges must have their condition hold on that predecessor.
            val satisfied = incoming.any { inEdge ->
                runtimeIdsOf(inEdge.from).any { predRuntimeId ->
                    val predOk = state.nodeStatus[predRuntimeId] == NodeStatus.COMPLETED
                    when (inEdge.type) {
                        EdgeType.CONDITIONAL -> {
                            val h = state.handoffMap[predRuntimeId]
                            predOk && h != null && evaluateCondition(inEdge.condition, h)
                        }
                        else -> predOk
                    }
                }
            }

            // Activate (or skip) every replica of the target.
            for (targetRuntimeId in runtimeIdsOf(edge.to)) {
                if (targetRuntimeId in state.dispatched) continue
                if (terminal(targetRuntimeId)) continue
                if (satisfied) {
                    state.nodeStatus[targetRuntimeId] = NodeStatus.PENDING
                    ready.add(targetRuntimeId)
                } else {
                    state.nodeStatus[targetRuntimeId] = NodeStatus.SKIPPED
                    // Record WHY. A skipped node with no explanation is
                    // indistinguishable from a bug in the routing.
                    val why = incoming
                        .filter { it.type == EdgeType.CONDITIONAL }
                        .mapNotNull { inEdge ->
                            runtimeIdsOf(inEdge.from).firstNotNullOfOrNull { pid ->
                                state.handoffMap[pid]?.let { h ->
                                    evaluateConditionExplained(inEdge.condition, h).explanation
                                }
                            }
                        }
                        .joinToString("; ")
                        .ifBlank { "no predecessor COMPLETED" }
                    val targetRole = graph.nodes.find { it.id == edge.to }?.role
                    if (targetRole != null) {
                        addTrace(state, targetRuntimeId, targetRole, "SKIPPED", why)
                        AgentRunShowcase.noteSkipped(
                            execContext.context, state.showcaseId, targetRole, why,
                        )
                    }
                }
            }
        }
        return ready.distinct()
    }

    /** Evaluate a simple condition string against handoff data. */
    private fun evaluateCondition(condition: String?, handoff: Handoff): Boolean =
        ConditionEvaluator.evaluate(condition, handoff).matched

    /**
     * Same as [evaluateCondition] but keeps the reason, so a skipped branch
     * shows up in the trace as "why" rather than a silent absence.
     */
    private fun evaluateConditionExplained(
        condition: String?,
        handoff: Handoff,
    ): ConditionEvaluator.Result = ConditionEvaluator.evaluate(condition, handoff)

    /** Handle handoff parse failure. */
    private fun handleParseFailure(execContext: ExecutionContext, node: AgentNode, response: String) {
        val state = execContext.state
        addTrace(state, node.id, node.role, "PARSE_FAILURE", "Could not parse handoff from response")
        state.nodeStatus[node.id] = NodeStatus.FAILED
    }

    /**
     * Record a trace event AND mirror it to AppLogger.
     *
     * The in-memory trace is only readable once `run` returns, which is exactly
     * no help when a node is hung or the app was killed mid-run. Logging every
     * event as it happens means Settings -> Logs shows how far a run got, which
     * is the first question when something does not finish.
     */
    private fun addTrace(state: GraphState, nodeId: String, role: AgentRole, action: String, details: String) {
        state.trace.add(TraceEvent(
            timestamp = System.currentTimeMillis(),
            nodeId = nodeId,
            role = role,
            action = action,
            details = details,
        ))
        val line = "[${state.taskId.take(8)}] $nodeId (${role.name}) $action: $details"
        when (action) {
            "OUT_OF_SCOPE", "FAILED", "MISSING_MODEL_ENTRY", "PARSE_FAILURE" ->
                com.openminis.app.logging.AppLogger.error(LOG_TAG, line)
            "INVALID_HANDOFF", "RETRY", "SCOPE_WARNING", "SKIPPED" ->
                com.openminis.app.logging.AppLogger.warning(LOG_TAG, line)
            else ->
                com.openminis.app.logging.AppLogger.info(LOG_TAG, line)
        }
    }

    /** Read artifact content from file system or minis:// URL. */
    private fun readArtifactContent(context: Context, path: String): String? {
        return try {
            if (path.startsWith("minis://")) {
                val realPath = path.replace("minis://", "/var/minis/")
                File(realPath).readText()
            } else {
                File(path).readText()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Write trace to offloads directory. */
    private fun writeTrace(taskId: String, trace: List<TraceEvent>, json: Json) {
        val traceFile = File(TRACE_DIR, "agent_graph_${taskId}.json")
        traceFile.parentFile?.mkdirs()
        val traceJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(TraceEvent.serializer()),
            trace,
        )
        traceFile.writeText(traceJson)
    }

    /** Write artifact index. */
    private fun writeArtifactIndex(artifactDir: String, artifacts: Map<String, String>) {
        val indexFile = File(artifactDir, "ARTIFACT_INDEX.json")
        val obj = org.json.JSONObject()
        for ((k, v) in artifacts) obj.put(k, v)
        indexFile.writeText(obj.toString(2))
    }

    private fun handleMissingModelEntry(
        execContext: ExecutionContext,
        node: AgentNode,
        runtimeId: String,
        reason: String,
    ) {
        val state = execContext.state
        addTrace(state, runtimeId, node.role, "MISSING_MODEL_ENTRY", reason)
        state.nodeStatus[runtimeId] = NodeStatus.FAILED
    }
}