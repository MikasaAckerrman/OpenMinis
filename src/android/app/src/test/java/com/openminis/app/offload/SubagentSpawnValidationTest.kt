package com.openminis.app.offload

import com.openminis.app.data.model.AgentGraph
import com.openminis.app.data.model.AgentNode
import com.openminis.app.data.model.AgentRole
import com.openminis.app.data.model.GraphConfig
import com.openminis.app.data.model.GraphRunResult
import com.openminis.app.data.model.Handoff
import com.openminis.app.data.model.HandoffStatus
import com.openminis.app.data.model.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-spawn-subagent-verify] Pins the 22.09.2026 subagent fix package:
 *
 *  1. SubagentExecutor spawns an ephemeral single-node graph — that node must
 *     declare a modelRole (or modelEntryId) or AgentGraph.validate() rejects
 *     the graph at save time and every spawn_subagent call dies before a
 *     single token is spent. This was the live "Invalid graph: Node
 *     'spawn-…': needs modelEntryId or modelRole" bug.
 *  2. HandoffValidator must tolerate the drift real models produce in the
 *     handoff block (case, spaces in role names) — a drifted spelling parsed
 *     as null and discarded a complete answer.
 *  3. GraphRunResult.lastExitResponse exists as the fallback for a worker
 *     that answered but botched the handoff block.
 *  4. ScopeGuard verdicts for the single-node spawn shape (empty
 *     mayDelegateTo, no ownedArtifact) stay correct.
 */
class SubagentSpawnValidationTest {

    private fun spawnNode(
        role: AgentRole = AgentRole.REQUIREMENTS_ANALYST,
        modelRole: String? = "planner",
    ): AgentNode {
        val node = AgentNode(
            id = "spawn-abcd1234",
            role = role,
            systemPrompt = "You are a ${role.name.lowercase().replace('_', ' ')}. Your ONE job: the task.",
            allowedTools = listOf("shell_execute", "file_read", "browser_use"),
            maxTurns = 8,
        )
        return if (modelRole == null) node else node.copy(modelRole = modelRole)
    }

    private fun spawnGraph(node: AgentNode): AgentGraph = AgentGraph(
        id = "ephemeral-${node.id}",
        name = "Spawn: ${node.role.name.lowercase().replace('_', ' ')}",
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

    @Test
    fun `spawn node with modelRole validates clean`() {
        val errors = spawnGraph(spawnNode()).validate()
        assertTrue("expected no errors, got: $errors", errors.isEmpty())
    }

    @Test
    fun `spawn node without any model source is rejected`() {
        // Regression pin for the original bug: the ephemeral node MUST carry a
        // model source or saveAgentGraph throws before the run starts.
        val errors = spawnGraph(spawnNode(modelRole = null)).validate()
        assertTrue(
            "expected 'needs modelEntryId or modelRole', got: $errors",
            errors.any { it.contains("needs modelEntryId or modelRole") },
        )
    }

    @Test
    fun `graph result exposes lastExitResponse with null default`() {
        val result = GraphRunResult(taskId = "t", status = RunStatus.SUCCESS)
        assertNull(result.lastExitResponse)
    }

    @Test
    fun `handoff with drifted case and spaces parses`() {
        val drifted = """
            Here is my analysis of the requirements.
            === HANDOFF START ===
            FROM: requirements analyst
            TO: orchestrator
            TASK_ID: t1
            STATUS: needs clarification
            DELIVERABLES:
            - Requirement 1: the timer must be visible to both sides
            NEXT_REQUIRED_ACTION:
            Ship the spec
            === HANDOFF END ===
        """.trimIndent()
        val parsed = HandoffValidator.parseHandoff(drifted)
        assertEquals(AgentRole.REQUIREMENTS_ANALYST, parsed?.from)
        assertEquals(AgentRole.ORCHESTRATOR, parsed?.to)
        assertEquals(HandoffStatus.NEEDS_CLARIFICATION, parsed?.status)
        assertTrue(HandoffValidator.validateResponse(drifted).isValid)
    }

    @Test
    fun `canonical handoff still parses and roundtrips`() {
        val handoff = Handoff(
            from = AgentRole.SENIOR_IMPLEMENTER,
            to = AgentRole.FINAL_GATEKEEPER,
            taskId = "t2",
            status = HandoffStatus.COMPLETE,
            deliverables = listOf("fix in a/b.kt"),
            successCriteria = listOf("tests green"),
            risks = emptyList(),
            nextAction = "review",
        )
        assertEquals(handoff, HandoffValidator.parseHandoff(HandoffValidator.buildHandoff(handoff)))
    }

    @Test
    fun `validateResponse still enforces the hard rules`() {
        val handoff = Handoff(
            from = AgentRole.SENIOR_IMPLEMENTER,
            to = AgentRole.FINAL_GATEKEEPER,
            taskId = "t3",
            status = HandoffStatus.COMPLETE,
            deliverables = listOf("fix in a/b.kt"),
            nextAction = "review",
        )
        assertTrue(
            "COMPLETE without deliverables must be invalid",
            !HandoffValidator.validateResponse(
                HandoffValidator.buildHandoff(handoff.copy(deliverables = emptyList())),
            ).isValid,
        )
        assertTrue(
            "empty NEXT_REQUIRED_ACTION must be invalid",
            !HandoffValidator.validateResponse(
                HandoffValidator.buildHandoff(handoff.copy(nextAction = "")),
            ).isValid,
        )
    }

    @Test
    fun `scope guard verdicts for the single-node spawn shape`() {
        val reviewNode = AgentNode(
            id = "n1",
            role = AgentRole.CODE_CORRECTNESS_REVIEWER,
            systemPrompt = "s",
        )
        val reviewHandoff = Handoff(
            from = AgentRole.CODE_CORRECTNESS_REVIEWER,
            to = AgentRole.ORCHESTRATOR,
            taskId = "t4",
            status = HandoffStatus.COMPLETE,
            deliverables = listOf("findings list: 3 logic errors"),
            nextAction = "fix them",
        )
        assertTrue(
            "matching FROM + prose must be Ok",
            ScopeGuard.check(reviewNode, reviewHandoff, "plain prose findings") is ScopeGuard.Verdict.Ok,
        )
        assertTrue(
            "FROM mismatch must be OutOfScope",
            ScopeGuard.check(
                reviewNode,
                reviewHandoff.copy(from = AgentRole.SENIOR_IMPLEMENTER),
                "x",
            ) is ScopeGuard.Verdict.OutOfScope,
        )
        assertTrue(
            "reviewer shipping a source file must be OutOfScope",
            ScopeGuard.check(
                reviewNode,
                reviewHandoff.copy(deliverables = listOf("fixed /var/minis/workspace/x.kt")),
                "```kotlin\nval x = 1\n```",
            ) is ScopeGuard.Verdict.OutOfScope,
        )
        assertTrue(
            "reviewer merely quoting code must be Suspicious, not fatal",
            ScopeGuard.check(reviewNode, reviewHandoff, "```kotlin\nval x = 1\n```")
                is ScopeGuard.Verdict.Suspicious,
        )
    }
}
