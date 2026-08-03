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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Core execution engine for agent graphs.
 * Runs a graph of agents with structured handoffs, artifact persistence, and tracing.
 */
object AgentGraphRunner {

    private const val ARTIFACT_DIR_BASE = "/var/minis/workspace"
    private const val TRACE_DIR = "/var/minis/offloads"

    /** In-memory state for a running graph. */
    data class GraphState(
        val graph: AgentGraph,
        val taskId: String,
        val artifactDir: String,
        val input: String,
        val sessionMap: MutableMap<String, String> = mutableMapOf(), // nodeId -> sessionId
        val handoffMap: MutableMap<String, Handoff> = mutableMapOf(), // nodeId -> handoff received
        val artifactIndex: MutableMap<String, String> = mutableMapOf(), // path -> content
        val trace: MutableList<TraceEvent> = mutableListOf(),
        val nodeStatus: MutableMap<String, NodeStatus> = mutableMapOf(),
    )

    enum class NodeStatus { PENDING, RUNNING, COMPLETED, FAILED, BLOCKED, SKIPPED }

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

        // Load graph
        val graph = providerRepo.loadAgentGraph(graphId)
            ?: return GraphRunResult(
                taskId = taskId,
                status = RunStatus.FAILED,
                error = "Graph not found: $graphId",
            )

        // Validate graph
        val errors = graph.validate()
        if (errors.isNotEmpty()) {
            return GraphRunResult(
                taskId = taskId,
                status = RunStatus.FAILED,
                error = "Graph validation failed: ${errors.joinToString(", ")}",
            )
        }

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
            state.nodeStatus[node.id] = NodeStatus.PENDING
        }

        val execContext = ExecutionContext(state, context, providerRepo, json)

        // Execute graph
        val result = executeGraph(execContext)

        // Write trace
        if (graph.config.enableTracing) {
            writeTrace(taskId, result.trace, json)
        }

        // Write final artifacts summary
        writeArtifactIndex(artifactDir, result.artifacts)

        result
    }

    /** Main graph execution loop. */
    private suspend fun executeGraph(execContext: ExecutionContext): GraphRunResult {
        val state = execContext.state
        val graph = state.graph

        // Queue of ready node IDs
        val readyQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
        readyQueue.add(graph.entryNodeId)

        // Track running nodes for parallelism limit
        val runningJobs = mutableMapOf<String, Job>()

        while (true) {
            // Check if any exit nodes completed
            val exitCompleted = graph.exitNodeIds.all { id ->
                state.nodeStatus[id] == NodeStatus.COMPLETED
            }
            if (exitCompleted && readyQueue.isEmpty() && runningJobs.isEmpty()) {
                break
            }

            // Check for deadlock (no ready nodes, but running jobs exist)
            if (readyQueue.isEmpty() && runningJobs.isNotEmpty()) {
                // Wait for a running job to complete
                kotlinx.coroutines.delay(100)
                continue
            }

            // Deadlock: no ready nodes, no running jobs, but not all exits completed
            if (readyQueue.isEmpty() && runningJobs.isEmpty()) {
                val incompleteExits = graph.exitNodeIds.filter { state.nodeStatus[it] != NodeStatus.COMPLETED }
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
                val node = graph.nodes.find { it.id == nodeId } ?: continue
                if (state.nodeStatus[nodeId] != NodeStatus.PENDING) continue

                val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    executeNode(execContext, node)
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
                // Queue successors
                queueSuccessors(execContext, nodeId)
            }

            // Small delay to prevent busy loop
            kotlinx.coroutines.delay(50)
        }

        // Determine final status
        val finalStatus = if (graph.exitNodeIds.all { state.nodeStatus[it] == NodeStatus.COMPLETED }) {
            RunStatus.SUCCESS
        } else if (graph.exitNodeIds.any { state.nodeStatus[it] == NodeStatus.COMPLETED }) {
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
    private suspend fun executeNode(execContext: ExecutionContext, node: AgentNode) {
        val state = execContext.state
        val context = execContext.context
        val providerRepo = execContext.providerRepo
        val json = execContext.json

        state.nodeStatus[node.id] = NodeStatus.RUNNING
        addTrace(state, node.id, node.role, "START", "Node execution started")

        // Resolve model entry: prefer modelRole (via agent.keys), fallback to modelEntryId
        val modelEntryId = if (node.modelRole.isNotBlank()) {
            providerRepo.resolveModelEntryForRole(node.modelRole)?.id
                ?: return handleMissingModelEntry(execContext, node, "modelRole '${node.modelRole}' not resolved")
        } else {
            node.modelEntryId.takeIf { it.isNotBlank() }
                ?: return handleMissingModelEntry(execContext, node, "modelEntryId is empty")
        }

        // Get or create session for this node
        val sessionId = state.sessionMap.getOrPut(node.id) {
            // Create session bound to node's model entry
            AgentSessionManager.createAndBindSession(context, modelEntryId)
        }

        // Build system prompt with role + tool allowlist
        val systemPrompt = buildSystemPrompt(node, state)

        // Build user message with handoff from predecessors + input
        val userMessage = buildUserMessage(node, state)

        // Send to model
        var response: String? = null
        var attempts = 0
        val maxAttempts = graph.config.retryPolicy.maxRetries + 1

        while (attempts < maxAttempts && response == null) {
            attempts++
            addTrace(state, node.id, node.role, "ATTEMPT", "Attempt $attempts/$maxAttempts")

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
                addTrace(state, node.id, node.role, "RETRY", "Status: ${promptResult.status}, waiting ${graph.config.retryPolicy.backoffMs}ms")
                kotlinx.coroutines.delay(graph.config.retryPolicy.backoffMs)
            } else {
                // Non-retryable error
                break
            }
        }

        if (response == null) {
            state.nodeStatus[node.id] = NodeStatus.FAILED
            addTrace(state, node.id, node.role, "FAILED", "All attempts exhausted")
            return
        }

        // Validate handoff
        val validation = HandoffValidator.validateResponse(response)
        if (!validation.isValid) {
            addTrace(state, node.id, node.role, "INVALID_HANDOFF", validation.message)
            // Retry with feedback
            if (attempts < maxAttempts) {
                val retryMessage = "Your previous response was invalid: ${validation.message}\n\nPlease respond with a valid handoff block."
                val retryResult = AgentSessionManager.sendAndWait(
                    context = context,
                    sessionId = sessionId,
                    text = retryMessage,
                    thinkingLevel = node.thinkingLevel,
                    timeoutMs = graph.config.defaultTimeoutMs,
                )
                if (retryResult.status == "Completed" && retryResult.responseText != null) {
                    val retryValidation = HandoffValidator.validateResponse(retryResult.responseText!!)
                    if (retryValidation.isValid) {
                        response = retryResult.responseText
                    }
                }
            }
        }

        val handoff = validation.handoff
            ?: HandoffValidator.parseHandoff(response!!)
            ?: return handleParseFailure(execContext, node, response!!)

        // Store handoff
        state.handoffMap[node.id] = handoff
        state.nodeStatus[node.id] = when (handoff.status) {
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
            val handoffPath = "${state.artifactDir}/${node.id}_handoff.md"
            File(handoffPath).writeText(HandoffValidator.buildHandoff(handoff))
            state.artifactIndex[handoffPath] = HandoffValidator.buildHandoff(handoff)
        }

        addTrace(state, node.id, node.role, "HANDOFF", "Status: ${handoff.status}, To: ${handoff.to}")
    }

    /** Build system prompt for an agent node. */
    private fun buildSystemPrompt(node: AgentNode, state: GraphState): String {
        val roleName = node.role.name.replace("_", " ")
        val toolList = ToolAllowlistEnforcer.formatAllowlist(node)
        return """
            You are the $roleName agent in a multi-agent coding pipeline.
            
            $toolList
            
            Your system instructions:
            ${node.systemPrompt}
            
            CRITICAL: You MUST follow the Unified Handoff Protocol. Every response must end with a valid handoff block:
            === HANDOFF START ===
            FROM: ${node.role.name}
            TO: [Next Agent Role]
            TASK_ID: ${state.taskId}
            STATUS: COMPLETE | BLOCKED | NEEDS_CLARIFICATION
            DELIVERABLES:
            - [list of artifacts]
            SUCCESS_CRITERIA_MET:
            - [what you verified]
            REMAINING_RISKS_OR_OPEN_QUESTIONS:
            - [any risks]
            NEXT_REQUIRED_ACTION:
            [one clear sentence]
            === HANDOFF END ===
            
            Do NOT include any text after the handoff block.
        """.trimIndent()
    }

    /** Build user message with context from predecessors. */
    private fun buildUserMessage(node: AgentNode, state: GraphState): String {
        val sb = StringBuilder()
        
        if (node.id == state.graph.entryNodeId) {
            sb.appendLine("TASK: ${state.input}")
            sb.appendLine("")
            sb.appendLine("You are the first agent. Begin your work.")
        } else {
            // Collect handoffs from all predecessor nodes
            val preds = state.graph.edges.filter { it.to == node.id }
            for (pred in preds) {
                val predHandoff = state.handoffMap[pred.from]
                if (predHandoff != null) {
                    sb.appendLine("HANDOFF FROM ${predHandoff.from.name}:")
                    sb.appendLine(HandoffValidator.buildHandoff(predHandoff))
                    sb.appendLine("")
                }
            }
            
            if (sb.isEmpty()) {
                sb.appendLine("TASK: ${state.input}")
                sb.appendLine("")
                sb.appendLine("No predecessor handoffs found. Begin work based on task.")
            }
        }
        
        return sb.toString()
    }

    /** Queue successor nodes based on edge types. */
    private fun queueSuccessors(execContext: ExecutionContext, completedNodeId: String) {
        val state = execContext.state
        val graph = state.graph
        val completedStatus = state.nodeStatus[completedNodeId] ?: return

        for (edge in graph.edges.filter { it.from == completedNodeId }) {
            val targetId = edge.to
            if (state.nodeStatus[targetId] != NodeStatus.PENDING) continue

            when (edge.type) {
                EdgeType.SEQUENTIAL -> {
                    if (completedStatus == NodeStatus.COMPLETED) {
                        state.nodeStatus[targetId] = NodeStatus.PENDING // ready to run
                    } else {
                        state.nodeStatus[targetId] = NodeStatus.SKIPPED
                    }
                }
                EdgeType.PARALLEL -> {
                    // All parallel targets become ready when ANY predecessor completes
                    state.nodeStatus[targetId] = NodeStatus.PENDING
                }
                EdgeType.RACE -> {
                    // First to complete wins - we'll handle this by marking others SKIPPED when one completes
                    state.nodeStatus[targetId] = NodeStatus.PENDING
                }
                EdgeType.FALLBACK -> {
                    // Try in order - only activate if previous failed
                    // For simplicity, activate all and let the first COMPLETED one win
                    state.nodeStatus[targetId] = NodeStatus.PENDING
                }
                EdgeType.BEST_OF_N -> {
                    state.nodeStatus[targetId] = NodeStatus.PENDING
                }
                EdgeType.CONDITIONAL -> {
                    // Evaluate condition on handoff
                    val handoff = state.handoffMap[completedNodeId]
                    if (handoff != null && evaluateCondition(edge.condition, handoff)) {
                        state.nodeStatus[targetId] = NodeStatus.PENDING
                    } else {
                        state.nodeStatus[targetId] = NodeStatus.SKIPPED
                    }
                }
            }
        }

        // For RACE/FALLBACK: when one node COMPLETES, mark siblings SKIPPED
        if (completedStatus == NodeStatus.COMPLETED) {
            val raceGroups = graph.edges
                .filter { it.from == completedNodeId && (it.type == EdgeType.RACE || it.type == EdgeType.FALLBACK) }
                .groupBy { it.from }
            for ((_, edges) in raceGroups) {
                val completedEdge = edges.firstOrNull { it.to == completedNodeId }
                if (completedEdge != null) {
                    for (edge in edges) {
                        if (edge.to != completedNodeId && state.nodeStatus[edge.to] == NodeStatus.PENDING) {
                            state.nodeStatus[edge.to] = NodeStatus.SKIPPED
                        }
                    }
                }
            }
        }
    }

    /** Evaluate a simple condition string against handoff data. */
    private fun evaluateCondition(condition: String?, handoff: Handoff): Boolean {
        if (condition == null || condition.isBlank()) return true
        // Simple evaluation: check if handoff.status matches
        // Could be extended with SpEL or similar
        return when {
            condition.contains("status ==") -> condition.contains(handoff.status.name)
            condition.contains("verdict ==") -> handoff.deliverables.any { it.contains("APPROVED") }
            else -> true
        }
    }

    /** Handle handoff parse failure. */
    private suspend fun handleParseFailure(execContext: ExecutionContext, node: AgentNode, response: String): NodeStatus {
        val state = execContext.state
        addTrace(state, node.id, node.role, "PARSE_FAILURE", "Could not parse handoff from response")
        state.nodeStatus[node.id] = NodeStatus.FAILED
        return NodeStatus.FAILED
    }

    /** Add trace event. */
    private fun addTrace(state: GraphState, nodeId: String, role: AgentRole, action: String, details: String) {
        state.trace.add(TraceEvent(
            timestamp = System.currentTimeMillis(),
            nodeId = nodeId,
            role = role,
            action = action,
            details = details,
        ))
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
        val traceJson = json.encodeToString(trace)
        traceFile.writeText(traceJson)
    }

    /** Write artifact index. */
    private fun writeArtifactIndex(artifactDir: String, artifacts: Map<String, String>) {
        val indexFile = File(artifactDir, "ARTIFACT_INDEX.json")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        indexFile.writeText(json.encodeToString(artifacts))
    }

    private suspend fun handleMissingModelEntry(
        execContext: ExecutionContext,
        node: AgentNode,
        reason: String,
    ): NodeStatus {
        val state = execContext.state
        addTrace(state, node.id, node.role, "MISSING_MODEL_ENTRY", reason)
        state.nodeStatus[node.id] = NodeStatus.FAILED
        return NodeStatus.FAILED
    }
}