package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

/**
 * [T-spawn-subagent] Runtime subagent spawning — Claude Code + OpenAI Agents SDK pattern.
 *
 * Two tools:
 * 1. `spawn_subagent` — LLM delegates a subtask to a specialized agent at runtime.
 *    The agent works in its own context window (isolated from main chat),
 *    with role-specific system prompt and scoped tools. Foreground blocks
 *    until done; background runs concurrently and delivers a notification.
 *
 * 2. `run_graph` — invoke any registered AgentGraph (or BuiltinGraphs preset)
 *    as a callable tool. This is OpenAI's "agents as tools" pattern: the
 *    graph's synthesized output becomes the tool result.
 *
 * Architecture: AgentGraphRunner already handles parallel execution, replicas,
 * scope enforcement, fan-in/out. These tools are a thin layer that creates
 * a runtime graph from a single role (spawn) or loads an existing one (run).
 */
object SubagentTools {

    const val SPAWN_TOOL_NAME = "spawn_subagent"
    const val RUN_GRAPH_TOOL_NAME = "run_graph"
    const val SPAWN_MANY_TOOL_NAME = "spawn_many"

    /**
     * Roles the LLM can spawn. Maps 1:1 to AgentRole enum values that have
     * system prompts in [com.openminis.app.data.model.AgentPrompts].
     */
    val SPAWNABLE_ROLES = listOf(
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

    fun spawnSubagentDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = SPAWN_TOOL_NAME,
        description = "Spawn a specialized subagent to handle a specific subtask. " +
            "The subagent runs in its own isolated context window with a role-specific " +
            "system prompt — it does not pollute this conversation. " +
            "Foreground (default): blocks until the subagent finishes and returns its output. " +
            "Background: the subagent runs concurrently while you continue working; " +
            "its result arrives as a notification you can check later. " +
            "Use this when a subtask would flood the main conversation with details, " +
            "or when you need parallel work (spawn multiple background subagents). " +
            "Example: spawn_subagent(role='CODE_CORRECTNESS_REVIEWER', task='Review the auth module for logic errors', background=true)",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user (e.g. 'Spawn code reviewer for auth module'). Use the user's language."),
            "role" to AgentToolParam("string", "The specialist role for the subagent",
                enumValues = SPAWNABLE_ROLES),
            "task" to AgentToolParam("string", "The specific subtask to delegate. Be detailed — the subagent sees ONLY this prompt plus the shared context, not the full conversation."),
            "background" to AgentToolParam("boolean", "false (default) = foreground: wait for result and return it. true = background: run concurrently, result arrives as notification later."),
        ),
        required = listOf("tool_title", "role", "task"),
        propertyOrdering = listOf("tool_title", "role", "task", "background"),
    )

    fun spawnManyDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = SPAWN_MANY_TOOL_NAME,
        description = "Spawn MULTIPLE subagents to work in PARALLEL on independent subtasks. " +
            "This is the batch tool: one call, one join point, all results numbered in the answer. " +
            "SAFETY (automatic): file paths mentioned in task texts are cross-checked — tasks that " +
            "touch the same file or directory are serialized automatically (run one after another, " +
            "not in parallel), so two agents can never corrupt the same file. Independent tasks run " +
            "concurrently (max 3 at once on this phone). " +
            "YOUR job before calling: make the tasks genuinely independent — different files, different " +
            "questions, no shared output. If two tasks MUST touch the same file, that is fine — the " +
            "engine will serialize them, but state it in the task texts. " +
            "Each subagent runs in its own isolated context with a role-specific system prompt and " +
            "sees ONLY its task — include every detail it needs in the task text. " +
            "Use for: parallel review of different modules, parallel research questions, " +
            "implementing changes in disjoint files. NOT for tasks that build on each other " +
            "(use sequential spawn_subagent or run_graph for pipelines).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user (e.g. 'Spawn 3 reviewers for auth module'). Use the user's language."),
            "agents" to AgentToolParam(
                "string",
                "A JSON array of agent specs, one object per agent: " +
                    "[{\"role\":\"CODE_CORRECTNESS_REVIEWER\",\"task\":\"Review /path/A.kt for logic errors\"}," +
                    " {\"role\":\"SECURITY_REVIEWER\",\"task\":\"Audit /path/B.kt for injection\"}]. " +
                    "Valid roles: " + SPAWNABLE_ROLES.joinToString(", ") + ". " +
                    "Keep tasks self-contained: the subagent sees ONLY its task text, not this conversation.",
            ),
            "mode" to AgentToolParam(
                "string",
                "auto (default): parallel with automatic conflict serialization. " +
                    "serial: run all one-by-one in array order (use when every task depends on the previous).",
                enumValues = listOf("auto", "serial"),
            ),
        ),
        required = listOf("tool_title", "agents"),
        propertyOrdering = listOf("tool_title", "agents", "mode"),
    )

    fun runGraphDefinition(): AgentToolDefinition = AgentToolDefinition(        name = RUN_GRAPH_TOOL_NAME,
        description = "Run an agent graph (multi-agent pipeline) as a tool. " +
            "The graph orchestrates multiple specialized agents in parallel or sequence, " +
            "and returns the synthesized result. Use this for complex multi-step work " +
            "that benefits from different specialists collaborating. " +
            "Built-in graphs: 'builtin-parallel-research' (2 analysts → synthesis), " +
            "'builtin-implement-review' (implement → correctness + security review → gate), " +
            "'builtin-deep-dive' (full pipeline: requirements → discovery → architecture → implement → 3 reviewers → gate).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary (e.g. 'Run deep dive on codebase'). Use the user's language."),
            "graph_id" to AgentToolParam("string", "The graph to run. Use a builtin preset id or a custom graph id.",
                enumValues = listOf(
                    "builtin-parallel-research",
                    "builtin-implement-review",
                    "builtin-deep-dive",
                )),
            "input" to AgentToolParam("string", "The task description / research question to feed the graph's entry node."),
        ),
        required = listOf("tool_title", "graph_id", "input"),
        propertyOrdering = listOf("tool_title", "graph_id", "input"),
    )
}
