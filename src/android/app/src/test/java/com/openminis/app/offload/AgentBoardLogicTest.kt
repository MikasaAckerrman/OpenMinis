package com.openminis.app.offload

import com.openminis.app.data.db.AgentTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-task-board] The board's decision core: readiness over JSON deps,
 * unmet-dep reporting, cycle detection, team summary shape. Pure JVM —
 * these are the rules the orchestrator's cross-turn decisions rest on,
 * so they are pinned like the prompt bytes are (AgentWorkerPromptTest
 * style). SQL is CRUD only and stays compile-checked by Room.
 */
class AgentBoardLogicTest {

    private fun task(
        id: String,
        status: String = AgentBoardLogic.STATUS_PENDING,
        deps: String = "[]",
        title: String = "task $id",
        createdAt: Long = 0L,
    ) = AgentTaskEntity(
        id = id,
        teamId = "team-1",
        title = title,
        description = title,
        roleRequired = "SENIOR_IMPLEMENTER",
        status = status,
        dependsOnTaskIds = deps,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun `independent pending tasks are all ready`() {
        val tasks = listOf(task("a"), task("b"), task("c"))
        assertEquals(listOf("a", "b", "c"), AgentBoardLogic.readyTaskIds(tasks))
    }

    @Test
    fun `task with completed dep is ready, with running dep is not`() {
        val tasks = listOf(
            task("a", AgentBoardLogic.STATUS_COMPLETED),
            task("b", AgentBoardLogic.STATUS_RUNNING),
            task("x", deps = """["a"]"""),
            task("y", deps = """["b"]"""),
        )
        assertEquals(listOf("x"), AgentBoardLogic.readyTaskIds(tasks))
    }

    @Test
    fun `failed dep is not satisfied and not silently passed`() {
        val tasks = listOf(
            task("a", AgentBoardLogic.STATUS_FAILED),
            task("x", deps = """["a"]"""),
        )
        assertEquals(emptyList<String>(), AgentBoardLogic.readyTaskIds(tasks))
        assertEquals(mapOf("x" to listOf("a")), AgentBoardLogic.unmetDeps(tasks))
    }

    @Test
    fun `missing dep id counts as unmet — pruned never silently satisfies`() {
        val tasks = listOf(task("x", deps = """["vanished"]"""))
        assertTrue(AgentBoardLogic.readyTaskIds(tasks).isEmpty())
        assertEquals(mapOf("x" to listOf("vanished")), AgentBoardLogic.unmetDeps(tasks))
    }

    @Test
    fun `transitive chain unblocks in dependency order`() {
        // c after b after a — only a is ready until it completes.
        val base = listOf(
            task("a"),
            task("b", deps = """["a"]"""),
            task("c", deps = """["b"]"""),
        )
        assertEquals(listOf("a"), AgentBoardLogic.readyTaskIds(base))
        val aDone = listOf(
            task("a", AgentBoardLogic.STATUS_COMPLETED),
            task("b", deps = """["a"]"""),
            task("c", deps = """["b"]"""),
        )
        assertEquals(listOf("b"), AgentBoardLogic.readyTaskIds(aDone))
    }

    @Test
    fun `diamond dependency needs both branches`() {
        val tasks = listOf(
            task("a", AgentBoardLogic.STATUS_COMPLETED),
            task("b1", AgentBoardLogic.STATUS_COMPLETED),
            task("b2"),
            task("join", deps = """["b1","b2"]"""),
        )
        assertEquals(emptyList<String>(), AgentBoardLogic.readyTaskIds(tasks))
        val b2Done = tasks.map {
            if (it.id == "b2") it.copy(status = AgentBoardLogic.STATUS_COMPLETED) else it
        }
        assertEquals(listOf("join"), AgentBoardLogic.readyTaskIds(b2Done))
    }

    @Test
    fun `cycle among pending tasks is detected`() {
        val tasks = listOf(
            task("a", deps = """["b"]"""),
            task("b", deps = """["a"]"""),
        )
        val cycle = AgentBoardLogic.findCycle(tasks)
        assertNotNull("a-b cycle must be reported, not deadlocked", cycle)
        assertTrue(cycle!!.containsAll(listOf("a", "b")))
    }

    @Test
    fun `no cycle in a clean chain`() {
        val tasks = listOf(
            task("a"),
            task("b", deps = """["a"]"""),
        )
        assertNull(AgentBoardLogic.findCycle(tasks))
    }

    @Test
    fun `team summary counts and caps the tail`() {
        val tasks = listOf(
            task("a", AgentBoardLogic.STATUS_COMPLETED, title = "map the codebase"),
            task("b", AgentBoardLogic.STATUS_FAILED, title = "fix the parser"),
            task("c", AgentBoardLogic.STATUS_RUNNING, title = "review the fix"),
        )
        val summary = AgentBoardLogic.teamSummary(tasks)
        assertTrue(summary.contains("3 task(s) — 1 ok, 1 failed"))
        assertTrue(summary.contains("1 running"))
        assertTrue(summary.contains("[COMPLETED] map the codebase"))
        assertTrue(summary.contains("[FAILED] fix the parser"))
    }

    @Test
    fun `empty team has no summary`() {
        assertEquals("", AgentBoardLogic.teamSummary(emptyList()))
    }

    @Test
    fun `json deps parse tolerates spaces and quotes`() {
        val tasks = listOf(
            task("a", AgentBoardLogic.STATUS_COMPLETED),
            task("x", deps = """[ "a" ]"""),
        )
        assertEquals(listOf("x"), AgentBoardLogic.readyTaskIds(tasks))
    }
}
