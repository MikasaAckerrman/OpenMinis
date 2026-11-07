package com.openminis.app.offload

import com.openminis.app.data.model.AgentRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B8] The delegation admission matrix, pinned in both directions.
 *
 * Before B8 the matrix verdict only fed allowNesting — a refused spawn was
 * never refused, the graph was built and RUN. The matrix is now the
 * admission gate at all three spawn entry points, so every cell here is
 * a hard refusal, not a tool downgrade.
 *
 * Two contract pins on the MAIN row (spawnerRole == null):
 *  - MAIN arms the FULL advertised surface — the spawn_subagent/spawn_many
 *    schemas advertise the whole SPAWNABLE list to the main chat, and MAIN
 *    worker spawns have worked since the feature shipped. This makes the
 *    pre-B8 gate bypass explicit instead of pretending it never existed.
 *  - The real security boundary is the SPAWNER side: a worker or reviewer
 *    session arms NOBODY, for any child, including custom agents.
 */
class SubagentRolesDelegationTest {

    @Test
    fun `the advertised spawnable list covers every role except the orchestrator`() {
        val advertised = SubagentRoles.SPAWNABLE.map { AgentRole.valueOf(it) }.toSet()
        assertEquals(
            "the tool schema must advertise every non-orchestrator role — a role in the enum but not in SPAWNABLE would be spawnable by no one",
            AgentRole.values().toSet() - AgentRole.ORCHESTRATOR,
            advertised,
        )
    }

    @Test
    fun `MAIN arms the full advertised surface`() {
        // Custom agents (childRole == null is the matrix's encoding of a
        // custom agent) — event-matrix fix A8 x A3.
        assertTrue(SubagentRoles.mayDelegate(null, null))
        // The entry delegator.
        assertTrue(SubagentRoles.mayDelegate(AgentRole.ORCHESTRATOR, null))
        // [B8] Every advertised role, workers included — the deviation made
        // explicit; the old test pinned EXPLORE as refused here.
        AgentRole.values().forEach { role ->
            assertTrue(
                "MAIN must be able to spawn ${role.name} — the spawn schema advertises it",
                SubagentRoles.mayDelegate(role, null),
            )
        }
    }

    @Test
    fun `an orchestrator hires workers and reviewers`() {
        assertTrue(
            "the orchestrator is the entry delegator, it must be able to hire workers",
            SubagentRoles.mayDelegate(AgentRole.SENIOR_IMPLEMENTER, AgentRole.ORCHESTRATOR),
        )
        assertTrue(SubagentRoles.mayDelegate(AgentRole.EXPLORE, AgentRole.ORCHESTRATOR))
        assertTrue(SubagentRoles.mayDelegate(AgentRole.SOLUTION_ARCHITECT, AgentRole.ORCHESTRATOR))
        assertTrue(SubagentRoles.mayDelegate(AgentRole.CODE_CORRECTNESS_REVIEWER, AgentRole.ORCHESTRATOR))
    }

    @Test
    fun `an orchestrator cannot arm another orchestrator`() {
        assertFalse(
            "the depth cap must hold",
            SubagentRoles.mayDelegate(AgentRole.ORCHESTRATOR, AgentRole.ORCHESTRATOR),
        )
    }

    @Test
    fun `an orchestrator cannot hire a custom agent`() {
        assertFalse(
            "custom agents are the main chat's prerogative",
            SubagentRoles.mayDelegate(null, AgentRole.ORCHESTRATOR),
        )
    }

    @Test
    fun `a worker spawner arms nobody — the B8 admission refusal`() {
        listOf(
            AgentRole.SENIOR_IMPLEMENTER,
            AgentRole.EXPLORE,
            AgentRole.DOCUMENTATION_AGENT,
        ).forEach { spawner ->
            assertFalse(
                "worker ${spawner.name} must not hire a custom agent",
                SubagentRoles.mayDelegate(null, spawner),
            )
            AgentRole.values().forEach { child ->
                assertFalse(
                    "worker ${spawner.name} must not arm ${child.name}",
                    SubagentRoles.mayDelegate(child, spawner),
                )
            }
        }
    }

    @Test
    fun `a reviewer spawner arms nobody either`() {
        listOf(
            AgentRole.CODE_CORRECTNESS_REVIEWER,
            AgentRole.SECURITY_REVIEWER,
            AgentRole.PERFORMANCE_REVIEWER,
            AgentRole.TEST_QUALITY_AUDITOR,
        ).forEach { spawner ->
            assertFalse(SubagentRoles.mayDelegate(null, spawner))
            AgentRole.values().forEach { child ->
                assertFalse(
                    "reviewer ${spawner.name} must not arm ${child.name}",
                    SubagentRoles.mayDelegate(child, spawner),
                )
            }
        }
    }

    @Test
    fun `a child with the nesting right keeps its full surface`() {
        val surface = listOf("shell_execute", "file_read", "spawn_subagent", "spawn_many", "cancel_subagent")
        assertEquals(surface, SubagentRoles.toolsForChild(surface, allowNesting = true))
    }

    @Test
    fun `a leaf child loses exactly the delegation tools`() {
        val surface = listOf("spawn_subagent", "shell_execute", "spawn_many", "file_read", "cancel_subagent", "file_write")
        assertEquals(
            listOf("shell_execute", "file_read", "file_write"),
            SubagentRoles.toolsForChild(surface, allowNesting = false),
        )
    }

    @Test
    fun `the nesting right follows the childCanNest formula`() {
        // Only an ORCHESTRATOR hired from the main chat nests — its
        // delegation tools survive the strip...
        val orchestratorSurface = SubagentRoles.defaultToolsForRole(AgentRole.ORCHESTRATOR)
        assertTrue("the orchestrator child advertises spawn tools", orchestratorSurface.contains("spawn_subagent"))
        assertEquals(
            orchestratorSurface,
            SubagentRoles.toolsForChild(orchestratorSurface, allowNesting = true),
        )
        // ...while a reviewer child (depth 3: ORCHESTRATOR -> REVIEWER ->
        // leaf) keeps its verifier surface minus the delegation tools.
        val reviewerSurface = SubagentRoles.defaultToolsForRole(AgentRole.SECURITY_REVIEWER)
        assertEquals(
            listOf("shell_execute", "file_read", "browser_use"),
            SubagentRoles.toolsForChild(reviewerSurface, allowNesting = false),
        )
    }
}
