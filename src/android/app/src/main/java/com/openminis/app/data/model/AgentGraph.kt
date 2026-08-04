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
) {
    val isValid: Boolean
        get() = role != AgentRole.ORCHESTRATOR || id == "orchestrator"
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