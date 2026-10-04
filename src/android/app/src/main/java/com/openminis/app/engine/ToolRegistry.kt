package com.openminis.app.engine

import com.openminis.app.data.model.AgentToolDefinition

/**
 * [T-tool-registry] The engine's tool surface: registration, lookup, and
 * the mode-filtered schema the model sees.
 *
 * Two layers of enforcement, deliberately:
 *  1. [schemaFor] — mutating (WRITE) tools vanish from the schema in PLAN,
 *     so the model cannot plan around a tool it will never get.
 *  2. [DefaultPermissionGate] — per-call backstop for what remains visible
 *     (EXECUTE tools stay visible in PLAN: read-only shell calls are
 *     legitimate exploration and are gated per-call).
 */
class ToolRegistry {

    private val tools = LinkedHashMap<String, EngineTool>()

    @Synchronized
    fun register(tool: EngineTool) {
        require(!tools.containsKey(tool.name)) {
            "duplicate tool registration: ${tool.name}"
        }
        tools[tool.name] = tool
    }

    fun find(name: String): EngineTool? = tools[name]

    fun names(): Set<String> = tools.keys.toSet()

    /**
     * The schema for one request build, filtered by mode. Rebuilt per call —
     * mode flips and tool toggles take effect on the next request, never
     * mid-schema-cache.
     */
    fun schemaFor(gate: DefaultPermissionGate): List<AgentToolDefinition> =
        tools.values
            .filter { gate.visibleInSchema(it.mutation) }
            .map { it.definition() }
}
