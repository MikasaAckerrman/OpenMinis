package com.openminis.app.config.collections

import com.openminis.app.config.ConfigCollection
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.data.model.AgentGraph
import com.openminis.app.data.model.AgentNode
import com.openminis.app.data.model.AgentRole
import com.openminis.app.data.model.EdgeType
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.serialization.json.Json

/**
 * Exposes AgentGraph fields under `graphs.<id>…`. Mirrors GroupsCollection.
 */
class GraphsCollection(
    private val repo: ProviderRepository,
) : ConfigCollection {
    override val basePath: String get() = "graphs"
    override val displayName: String get() = "Agent Graphs"
    override val description: String get() = "Multi-agent graph configurations for autonomous coding pipelines."
    override val addable: Boolean get() = true
    override val removable: Boolean get() = true
    override val risk: ConfigRisk get() = ConfigRisk.SENSITIVE
    override val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    override fun childIds(): List<String> = repo.listAgentGraphsSync().map { it.id }

    override fun fields(forId: String): List<ConfigField> {
        val graph = repo.loadAgentGraphSync(forId)
        if (graph == null) return emptyList()
        return listOf(
            nameField(forId),
            nodesField(forId),
            edgesField(forId),
            entryNodeField(forId),
            exitNodesField(forId),
            configField(forId),
        )
    }

    override fun add(payload: ConfigValue): String {
        val obj = (payload as? ConfigValue.Obj)?.value
            ?: throw ConfigError.InvalidValue("Expected JSON object")
        
        val name = (obj["name"] as? ConfigValue.Str)?.value
            ?: throw ConfigError.InvalidValue("`name` required")
        if (name.isEmpty()) throw ConfigError.InvalidValue("`name` required")

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        
        // Parse nodes
        val nodes = mutableListOf<AgentNode>()
        val nodesArr = (obj["nodes"] as? ConfigValue.Arr)?.value ?: emptyList()
        for (n in nodesArr) {
            val nObj = (n as? ConfigValue.Obj)?.value ?: continue
            val roleStr = (nObj["role"] as? ConfigValue.Str)?.value ?: continue
            val role = runCatching { AgentRole.valueOf(roleStr) }.getOrNull()
                ?: throw ConfigError.InvalidValue("Unknown agent role: $roleStr")
            val id = (nObj["id"] as? ConfigValue.Str)?.value ?: java.util.UUID.randomUUID().toString()
            val systemPrompt = (nObj["systemPrompt"] as? ConfigValue.Str)?.value ?: ""
            val modelEntryId = (nObj["modelEntryId"] as? ConfigValue.Str)?.value ?: ""
            val modelRole = (nObj["modelRole"] as? ConfigValue.Str)?.value ?: ""
            val allowedTools = (nObj["allowedTools"] as? ConfigValue.Arr)?.value?.mapNotNull { (it as? ConfigValue.Str)?.value } ?: emptyList()
            // Reject unknown tool names at config time. A silent typo would
            // otherwise hand the node an allowlist that matches nothing —
            // and since an empty-after-filter schema is indistinguishable
            // from "no tools", the agent would fail with no explanation.
            val unknown = com.openminis.app.offload.ToolAllowlistEnforcer.unknownTools(allowedTools)
            if (unknown.isNotEmpty()) {
                throw ConfigError.InvalidValue(
                    "Unknown tool(s) for node '$id': ${unknown.joinToString(", ")}. " +
                        "Valid: ${com.openminis.app.offload.ToolAllowlistEnforcer.ALL_TOOLS.sorted().joinToString(", ")}, memory"
                )
            }
            val maxTurns = (nObj["maxTurns"] as? ConfigValue.Int)?.value ?: 10
            val thinkingLevel = (nObj["thinkingLevel"] as? ConfigValue.Str)?.value
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { com.openminis.app.data.model.ThinkingLevel.valueOf(it) }.getOrNull() }
            val temperature = (nObj["temperature"] as? ConfigValue.Double)?.value?.toFloat()
            
            // [T-agent-graph-scope] Scope + parallelism fields.
            val ownedArtifact = (nObj["ownedArtifact"] as? ConfigValue.Str)?.value ?: ""
            val mayDelegateTo = (nObj["mayDelegateTo"] as? ConfigValue.Arr)?.value
                ?.mapNotNull { (it as? ConfigValue.Str)?.value }
                ?.map { raw ->
                    runCatching { AgentRole.valueOf(raw) }.getOrNull()
                        ?: throw ConfigError.InvalidValue("Unknown role in mayDelegateTo: $raw")
                }
                ?: emptyList()
            val replicas = (nObj["replicas"] as? ConfigValue.Int)?.value ?: 1
            val shardHint = (nObj["shardHint"] as? ConfigValue.Arr)?.value
                ?.mapNotNull { (it as? ConfigValue.Str)?.value }
                ?: emptyList()

            nodes.add(AgentNode(
                id = id,
                role = role,
                systemPrompt = systemPrompt,
                allowedTools = allowedTools,
                modelEntryId = modelEntryId,
                modelRole = modelRole,
                maxTurns = maxTurns,
                thinkingLevel = thinkingLevel,
                temperature = temperature,
                ownedArtifact = ownedArtifact,
                mayDelegateTo = mayDelegateTo,
                replicas = replicas,
                shardHint = shardHint,
            ))
        }

        // Parse edges
        val edges = mutableListOf<com.openminis.app.data.model.AgentEdge>()
        val edgesArr = (obj["edges"] as? ConfigValue.Arr)?.value ?: emptyList()
        for (e in edgesArr) {
            val eObj = (e as? ConfigValue.Obj)?.value ?: continue
            val from = (eObj["from"] as? ConfigValue.Str)?.value ?: continue
            val to = (eObj["to"] as? ConfigValue.Str)?.value ?: continue
            val typeStr = (eObj["type"] as? ConfigValue.Str)?.value ?: "SEQUENTIAL"
            val type = runCatching { EdgeType.valueOf(typeStr) }.getOrNull()
                ?: throw ConfigError.InvalidValue("Unknown edge type: $typeStr")
            val condition = (eObj["condition"] as? ConfigValue.Str)?.value
            // Reject an unparseable condition at save time. ConditionEvaluator
            // fails closed on bad syntax, so a typo would silently disable the
            // branch — visible only as a mysteriously skipped node mid-run.
            if (!com.openminis.app.offload.ConditionEvaluator.isSyntaxValid(condition)) {
                throw ConfigError.InvalidValue(
                    "Edge $from -> $to has an unparseable condition: '$condition'. " +
                        "Supported: complexity >= L2 | status == COMPLETE | " +
                        "verdict contains APPROVED | deliverables contains <text> | always, " +
                        "joined by &&"
                )
            }

            edges.add(com.openminis.app.data.model.AgentEdge(from = from, to = to, type = type, condition = condition))
        }

        val entryNodeId = (obj["entryNodeId"] as? ConfigValue.Str)?.value ?: nodes.firstOrNull()?.id ?: ""
        val exitNodeIds = (obj["exitNodeIds"] as? ConfigValue.Arr)?.value?.mapNotNull { (it as? ConfigValue.Str)?.value } ?: emptyList()

        // Parse config
        val configObj = (obj["config"] as? ConfigValue.Obj)?.value
        val maxParallelNodes = configObj?.get("maxParallelNodes")?.let { (it as? ConfigValue.Int)?.value ?: 4 } ?: 4
        val defaultTimeoutMs = configObj?.get("defaultTimeoutMs")
            ?.let { (it as? ConfigValue.Int)?.value?.toLong() } ?: 120_000L

        val graph = AgentGraph(
            name = name,
            nodes = nodes,
            edges = edges,
            entryNodeId = entryNodeId,
            exitNodeIds = exitNodeIds,
            config = com.openminis.app.data.model.GraphConfig(
                maxParallelNodes = maxParallelNodes,
                defaultTimeoutMs = defaultTimeoutMs,
            ),
        )

        repo.saveAgentGraphSync(graph)
        return graph.id
    }

    override fun remove(id: String) {
        if (repo.loadAgentGraphSync(id) == null) throw ConfigError.UnknownPath("graphs.$id")
        repo.deleteAgentGraphSync(id)
    }

    private fun graph(id: String): AgentGraph? = repo.loadAgentGraphSync(id)

    /**
     * AgentGraph is an immutable data class (all fields `val`), so mutation
     * goes through `copy()` — the transform returns the NEW graph rather than
     * editing in place (which is what GroupsCollection can do because
     * ModelGroup's fields are `var`).
     */
    private fun mutate(id: String, transform: (AgentGraph) -> AgentGraph) {
        val g = graph(id) ?: throw ConfigError.UnknownPath("graphs.$id")
        repo.saveAgentGraphSync(transform(g))
    }

    private fun nameField(id: String): ConfigField =
        ClosureField(
            path = "graphs.$id.name",
            displayName = "Name",
            description = "User-visible label.",
            valueSchema = ConfigSchema.Str(maxLength = 200),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            reader = { val g = graph(id) ?: return@ClosureField ConfigValue.Null; ConfigValue.Str(g.name) },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                mutate(id) { g -> g.copy(name = s) }
            },
        )

    private fun nodesField(id: String): ConfigField =
        ClosureField(
            path = "graphs.$id.nodes",
            displayName = "Nodes",
            description = "Array of agent nodes.",
            valueSchema = ConfigSchema.Array(ConfigSchema.Json),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            reader = {
                val g = graph(id) ?: return@ClosureField ConfigValue.Null
                val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
                ConfigValue.Arr(g.nodes.map { node ->
                    ConfigValue.Obj(mapOf(
                        "id" to ConfigValue.Str(node.id),
                        "role" to ConfigValue.Str(node.role.name),
                        "systemPrompt" to ConfigValue.Str(node.systemPrompt),
                        "modelEntryId" to ConfigValue.Str(node.modelEntryId),
                        "modelRole" to ConfigValue.Str(node.modelRole),
                        "allowedTools" to ConfigValue.Arr(node.allowedTools.map { ConfigValue.Str(it) }),
                        "maxTurns" to ConfigValue.Int(node.maxTurns),
                        "thinkingLevel" to ConfigValue.Str(node.thinkingLevel?.name ?: ""),
                        "temperature" to ConfigValue.Double(node.temperature?.toDouble() ?: 0.0),
                        "ownedArtifact" to ConfigValue.Str(node.ownedArtifact),
                        "mayDelegateTo" to ConfigValue.Arr(node.mayDelegateTo.map { ConfigValue.Str(it.name) }),
                        "replicas" to ConfigValue.Int(node.replicas),
                        "shardHint" to ConfigValue.Arr(node.shardHint.map { ConfigValue.Str(it) }),
                    ))
                })
            },
            writer = { /* complex write handled via full graph replace */ },
        )

    private fun edgesField(id: String): ConfigField =
        ClosureField(
            path = "graphs.$id.edges",
            displayName = "Edges",
            description = "Array of edges between nodes.",
            valueSchema = ConfigSchema.Array(ConfigSchema.Json),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            reader = {
                val g = graph(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Arr(g.edges.map { edge ->
                    ConfigValue.Obj(mapOf(
                        "from" to ConfigValue.Str(edge.from),
                        "to" to ConfigValue.Str(edge.to),
                        "type" to ConfigValue.Str(edge.type.name),
                        "condition" to ConfigValue.Str(edge.condition ?: ""),
                    ))
                })
            },
            writer = { /* complex write handled via full graph replace */ },
        )

    private fun entryNodeField(id: String): ConfigField =
        ClosureField(
            path = "graphs.$id.entryNodeId",
            displayName = "Entry Node",
            description = "Starting node ID.",
            valueSchema = ConfigSchema.Str(),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            reader = { val g = graph(id) ?: return@ClosureField ConfigValue.Null; ConfigValue.Str(g.entryNodeId) },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                mutate(id) { g -> g.copy(entryNodeId = s) }
            },
        )

    private fun exitNodesField(id: String): ConfigField =
        ClosureField(
            path = "graphs.$id.exitNodeIds",
            displayName = "Exit Nodes",
            description = "Terminal node IDs.",
            valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            reader = { val g = graph(id) ?: return@ClosureField ConfigValue.Null; ConfigValue.Arr(g.exitNodeIds.map { ConfigValue.Str(it) }) },
            writer = { v ->
                val arr = (v as? ConfigValue.Arr)?.value ?: throw ConfigError.TypeMismatch("array")
                val ids = arr.mapNotNull { (it as? ConfigValue.Str)?.value }
                mutate(id) { g -> g.copy(exitNodeIds = ids) }
            },
        )

    private fun configField(id: String): ConfigField =
        ClosureField(
            path = "graphs.$id.config",
            displayName = "Graph Config",
            description = "Execution config.",
            valueSchema = ConfigSchema.Json,
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            reader = {
                val g = graph(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Obj(mapOf(
                    "maxParallelNodes" to ConfigValue.Int(g.config.maxParallelNodes),
                    "defaultTimeoutMs" to ConfigValue.Int(g.config.defaultTimeoutMs.toInt()),
                    "artifactDir" to ConfigValue.Str(g.config.artifactDir),
                    "enableTracing" to ConfigValue.Bool(g.config.enableTracing),
                ))
            },
            writer = { /* handled via full graph replace */ },
        )
}