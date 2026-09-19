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

    fun runGraphDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = RUN_GRAPH_TOOL_NAME,
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
