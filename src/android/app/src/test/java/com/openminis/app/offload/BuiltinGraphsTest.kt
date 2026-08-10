package com.openminis.app.offload

import com.openminis.app.data.model.AgentRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants of the shipped graphs. These are cheap to check and expensive to
 * get wrong: a built-in is what every user runs before configuring anything.
 */
class BuiltinGraphsTest {

    @Test
    fun `no builtin node runs parallel implementer replicas`() {
        // Cognition's "Don't Build Multi-Agents" (principle 2: actions carry
        // implicit decisions) and Anthropic's own multi-agent write-up both land
        // on the same point for coding: parallel implementers make conflicting
        // decisions even when their files never overlap. Measured cost is not
        // merge conflicts, it is incoherent output. Review parallelises; writing
        // does not.
        for (graph in BuiltinGraphs.all()) {
            for (node in graph.nodes) {
                if (node.role == AgentRole.SENIOR_IMPLEMENTER) {
                    assertEquals(
                        "graph '${graph.id}' node '${node.id}' must run a single implementer",
                        1,
                        node.replicas,
                    )
                }
            }
        }
    }

    @Test
    fun `every builtin node has a tool budget`() {
        // maxTurns was a dead field until the planner burned 14 tool calls
        // without producing a plan (run 9eb70345). A node with no budget is a
        // node that can wander for the whole run.
        for (graph in BuiltinGraphs.all()) {
            for (node in graph.nodes) {
                assertTrue(
                    "graph '${graph.id}' node '${node.id}' has maxTurns=${node.maxTurns}",
                    node.maxTurns > 0,
                )
            }
        }
    }

    @Test
    fun `the planner's budget is tighter than the implementer's`() {
        // Planning is deciding, not exploring. The implementer is the role that
        // legitimately runs long tool loops.
        for (graph in BuiltinGraphs.all()) {
            val planner = graph.nodes.firstOrNull { it.role == AgentRole.ORCHESTRATOR }
            val impl = graph.nodes.firstOrNull { it.role == AgentRole.SENIOR_IMPLEMENTER }
            if (planner != null && impl != null) {
                assertTrue(
                    "graph '${graph.id}': planner ${planner.maxTurns} should be under " +
                        "implementer ${impl.maxTurns}",
                    planner.maxTurns < impl.maxTurns,
                )
            }
        }
    }

    @Test
    fun `no builtin prompt still instructs a node to split work into shards`() {
        // The shard wording only made sense with two implementers. Leaving it in
        // would tell the architect to design for an arrangement that no longer
        // exists — the worst kind of stale prompt, because it reads as correct.
        for (graph in BuiltinGraphs.all()) {
            for (node in graph.nodes) {
                val prompt = node.systemPrompt
                assertTrue(
                    "graph '${graph.id}' node '${node.id}' still mentions SHARD A/B",
                    !prompt.contains("SHARD A") && !prompt.contains("SHARD B"),
                )
                assertTrue(
                    "graph '${graph.id}' node '${node.id}' still claims a sibling implementer",
                    !prompt.contains("sibling implementer"),
                )
            }
        }
    }

    @Test
    fun `reviewers cannot write`() {
        // The one barrier that is real rather than advisory: a tool absent from
        // the allowlist never reaches the model's schema.
        val writeTools = setOf("file_write", "file_edit")
        val reviewRoles = setOf(
            AgentRole.CODE_CORRECTNESS_REVIEWER,
            AgentRole.SECURITY_REVIEWER,
            AgentRole.PERFORMANCE_REVIEWER,
        )
        for (graph in BuiltinGraphs.all()) {
            for (node in graph.nodes.filter { it.role in reviewRoles }) {
                for (tool in node.allowedTools) {
                    assertTrue(
                        "graph '${graph.id}' node '${node.id}' can $tool",
                        tool !in writeTools,
                    )
                }
            }
        }
    }
}
