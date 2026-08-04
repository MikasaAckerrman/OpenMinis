package com.openminis.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class AgentRole {
    ORCHESTRATOR,
    REQUIREMENTS_ANALYST,
    CODEBASE_DISCOVERY,
    SOLUTION_ARCHITECT,
    INDEPENDENT_TEST_DESIGNER,
    SENIOR_IMPLEMENTER,
    CODE_CORRECTNESS_REVIEWER,
    SECURITY_REVIEWER,
    PERFORMANCE_REVIEWER,
    DEPENDENCY_GUARDIAN,
    TEST_QUALITY_AUDITOR,
    FINAL_GATEKEEPER,
    DOCUMENTATION_AGENT,
}

@Serializable
enum class EdgeType {
    SEQUENTIAL,
    PARALLEL,
    RACE,
    BEST_OF_N,
    FALLBACK,
    CONDITIONAL,
}

@Serializable
data class AgentNode(
    val id: String = UUID.randomUUID().toString(),
    val role: AgentRole,
    val systemPrompt: String,
    val allowedTools: List<String> = emptyList(),
    val modelEntryId: String = "",
    val modelRole: String = "",  // Alternative to modelEntryId: "planner" | "analyst" | "architect" | "coder" | "reviewer" | "tester"
    val maxTurns: Int = 10,
    val thinkingLevel: ThinkingLevel? = null,
    val temperature: Float? = null,

    /**
     * [T-agent-graph-scope] The ONE artifact type this node is allowed to
     * produce. Surfaced verbatim in the system prompt and used by the scope
     * guard: if the handoff's deliverables do not look like this artifact,
     * the run is stopped instead of letting the node quietly do a neighbour's
     * job. Free-form on purpose — a closed enum would need a code change for
     * every new pipeline shape.
     *
     * Example: "architecture design document", "production code diff",
     * "test files", "review findings list".
     */
    val ownedArtifact: String = "",

    /**
     * Roles this node may address in `TO:`. Empty = anyone (the graph's edges
     * still decide the actual route; this only constrains what the node is
     * told it may request). Keeping it explicit is what stops a thinker from
     * inventing a handoff to an agent that does not exist in the graph.
     */
    val mayDelegateTo: List<AgentRole> = emptyList(),

    /**
     * [T-agent-graph-parallel] How many independent instances of this node to
     * run at once. >1 spawns `id#1`, `id#2`, … each with its OWN session, so
     * their contexts never mix. Combined with [shardHint] this is how two
     * implementers split the work instead of duplicating it.
     *
     * Sessions being separate is also why per-role API keys matter: N replicas
     * of a coder node hit N independent provider instances, spreading spend.
     */
    val replicas: Int = 1,

    /**
     * Per-replica scope, injected as "YOUR SHARD" in the prompt. Index i gets
     * `shardHint[i]`. Without this two replicas receive identical prompts and
     * write the same file twice.
     *
     * Example for replicas=2:
     *   ["modules listed FIRST in the ownership map",
     *    "modules listed SECOND in the ownership map"]
     */
    val shardHint: List<String> = emptyList(),
) {
    val isValid: Boolean
        get() = role != AgentRole.ORCHESTRATOR || id == "orchestrator"

    /** Replica ids this node expands to. Single-replica nodes keep their id. */
    fun replicaIds(): List<String> =
        if (replicas <= 1) listOf(id) else (1..replicas).map { "$id#$it" }

    /** Shard text for replica [index] (0-based), or "" when unsharded. */
    fun shardFor(index: Int): String = shardHint.getOrElse(index) { "" }
}

@Serializable
data class AgentEdge(
    val from: String,
    val to: String,
    val type: EdgeType = EdgeType.SEQUENTIAL,
    val condition: String? = null,
    val config: EdgeConfig = EdgeConfig(),
)

@Serializable
data class EdgeConfig(
    val raceTimeoutMs: Long = 30_000,
    val bestOfNCount: Int = 3,
    val fallbackMaxRetries: Int = 1,
)

@Serializable
data class AgentGraph(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val version: Int = 1,
    val nodes: List<AgentNode> = emptyList(),
    val edges: List<AgentEdge> = emptyList(),
    val entryNodeId: String,
    val exitNodeIds: List<String> = emptyList(),
    val config: GraphConfig = GraphConfig(),
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        val nodeIds = nodes.map { it.id }.toSet()
        
        if (entryNodeId !in nodeIds) {
            errors.add("entryNodeId '$entryNodeId' not found in nodes")
        }
        for (exitId in exitNodeIds) {
            if (exitId !in nodeIds) {
                errors.add("exitNodeId '$exitId' not found in nodes")
            }
        }
        for (edge in edges) {
            if (edge.from !in nodeIds) errors.add("Edge from '${edge.from}' not found in nodes")
            if (edge.to !in nodeIds) errors.add("Edge to '${edge.to}' not found in nodes")
        }
        // Check for cycles (simple DFS)
        val visited = mutableSetOf<String>()
        val recStack = mutableSetOf<String>()
        fun dfs(nodeId: String): Boolean {
            if (nodeId in recStack) return true
            if (nodeId in visited) return false
            visited.add(nodeId)
            recStack.add(nodeId)
            for (edge in edges.filter { it.from == nodeId }) {
                if (dfs(edge.to)) return true
            }
            recStack.remove(nodeId)
            return false
        }
        if (dfs(entryNodeId)) {
            errors.add("Graph contains cycles (not allowed for SEQUENTIAL/PARALLEL edges)")
        }

        // Duplicate ids would make status/session maps collide silently.
        nodes.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach {
            errors.add("Duplicate node id '$it'")
        }

        // A '#' in a config id collides with the replica suffix scheme
        // (`implementer#1`), which would make runtime ids ambiguous.
        nodes.filter { it.id.contains('#') }.forEach {
            errors.add("Node id '${it.id}' must not contain '#' — reserved for replica suffixes")
        }

        for (node in nodes) {
            if (node.replicas < 1) {
                errors.add("Node '${node.id}': replicas must be >= 1, got ${node.replicas}")
            }
            // Replicas without shards get identical prompts and duplicate work.
            if (node.replicas > 1 && node.shardHint.size < node.replicas) {
                errors.add(
                    "Node '${node.id}': replicas=${node.replicas} needs ${node.replicas} " +
                        "shardHint entries (has ${node.shardHint.size}) — otherwise every " +
                        "replica receives the same prompt and duplicates the work"
                )
            }
            // A node must declare either an explicit entry or a role to resolve.
            if (node.modelEntryId.isBlank() && node.modelRole.isBlank()) {
                errors.add("Node '${node.id}': needs modelEntryId or modelRole")
            }
            // mayDelegateTo pointing at a role no node implements is a dead end.
            val presentRoles = nodes.map { it.role }.toSet()
            node.mayDelegateTo.filterNot { it in presentRoles }.forEach { missing ->
                errors.add(
                    "Node '${node.id}': mayDelegateTo lists $missing, but no node in " +
                        "this graph has that role"
                )
            }
        }

        return errors
    }
}

@Serializable
data class GraphConfig(
    val maxParallelNodes: Int = 4,
    val defaultTimeoutMs: Long = 120_000,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val artifactDir: String = "/var/minis/workspace",
    val enableTracing: Boolean = true,
)

@Serializable
data class RetryPolicy(
    val maxRetries: Int = 2,
    val backoffMs: Long = 2000,
    val retryOn: List<String> = listOf("Timeout", "Error"),
)

@Serializable
data class Handoff(
    val from: AgentRole,
    val to: AgentRole,
    val taskId: String,
    val status: HandoffStatus,
    val deliverables: List<String> = emptyList(),
    val successCriteria: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    val nextAction: String = "",
)

@Serializable
enum class HandoffStatus {
    COMPLETE,
    BLOCKED,
    NEEDS_CLARIFICATION,
}

@Serializable
data class GraphRunResult(
    val taskId: String,
    val status: RunStatus,
    val artifacts: Map<String, String> = emptyMap(),
    val trace: List<TraceEvent> = emptyList(),
    val error: String? = null,
)

@Serializable
enum class RunStatus {
    SUCCESS,
    FAILED,
    PARTIAL,
    ESCALATED,
}

@Serializable
data class TraceEvent(
    val timestamp: Long,
    val nodeId: String,
    val role: AgentRole,
    val action: String,
    val details: String,
)

// Extend ProviderConfig with agent graphs
// (Add this field to ProviderConfig in ProviderConfig.kt)
// val agentGraphs: MutableList<AgentGraph> = mutableListOf()