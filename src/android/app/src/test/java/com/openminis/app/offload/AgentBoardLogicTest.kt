package com.openminis.app.offload

import com.openminis.app.data.db.AgentTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // join waits for BOTH branches: b2 pending blocks it. b2 itself is
        // independent and legitimately ready — the first draft of this test
        // asserted an empty ready set, which would mean the readiness rule
        // blocks unrelated tasks (a far worse bug than the one under test).
        assertEquals(listOf("b2"), AgentBoardLogic.readyTaskIds(tasks))
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
        // The id is the handle depends_on references — it must be visible.
        assertTrue(summary.contains("[COMPLETED] a: map the codebase"))
        assertTrue(summary.contains("[FAILED] b: fix the parser"))
    }

    @Test
    fun `running row older than an hour is marked stale`() {
        val now = 10_000_000L
        val fresh = listOf(
            task("r1", AgentBoardLogic.STATUS_RUNNING, createdAt = now - 60_000, title = "fresh run"),
        )
        assertFalse(AgentBoardLogic.teamSummary(fresh, now = now).contains("stale"))
        val stale = listOf(
            task(
                "r2", AgentBoardLogic.STATUS_RUNNING, createdAt = now - 7_200_000,
                title = "zombie run",
            ).copy(updatedAt = now - 7_200_000),
        )
        val text = AgentBoardLogic.teamSummary(stale, now = now)
        assertTrue(text.contains("stale"))
        assertTrue(text.contains("likely died"))
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

    // ── [T-needs-you-lane] Wishlist No.12: scan-first intervention lane ──

    @Test
    fun `needs-you lane is empty when everything is healthy`() {
        val now = 10_000_000L
        val tasks = listOf(
            task("a", AgentBoardLogic.STATUS_COMPLETED),
            task("b", AgentBoardLogic.STATUS_PENDING),
            task("c", AgentBoardLogic.STATUS_RUNNING, createdAt = now - 60_000),
        )
        assertEquals("", AgentBoardLogic.needsYouSection(tasks, now = now))
    }

    @Test
    fun `needs-you lane groups failed, blocked and stale with action hints`() {
        val now = 10_000_000L
        val tasks = listOf(
            task("ok", AgentBoardLogic.STATUS_COMPLETED),
            task("f1", AgentBoardLogic.STATUS_FAILED, title = "fix the thing"),
            task("stale1", AgentBoardLogic.STATUS_RUNNING, createdAt = now - 7_200_000)
                .copy(updatedAt = now - 7_200_000, title = "zombie"),
            task("b1", AgentBoardLogic.STATUS_BLOCKED, title = "needs a decision"),
        )
        val text = AgentBoardLogic.needsYouSection(tasks, now = now)
        // Header counts all three, ignores the healthy one.
        assertTrue(text.startsWith("⚠ Needs you (3):"))
        // Severity order: FAILED before BLOCKED before stale RUNNING.
        val iF = text.indexOf("[FAILED] f1")
        val iB = text.indexOf("[BLOCKED] b1")
        val iS = text.indexOf("stale1")
        assertTrue(iF in 0 until iB)
        assertTrue(iB in (iF + 1) until iS)
        // Each line routes a decision, not history.
        assertTrue(text.contains("re-spawn or drop"))
        assertTrue(text.contains("read the block reason"))
        assertTrue(text.contains("likely died; re-spawn"))
    }

    @Test
    fun `needs-you lane caps lines and reports the overflow`() {
        val now = 10_000_000L
        val tasks = (1..10).map { i ->
            task("f$i", AgentBoardLogic.STATUS_FAILED, title = "fail $i")
        }
        val text = AgentBoardLogic.needsYouSection(tasks, maxLines = 8, now = now)
        assertTrue(text.startsWith("⚠ Needs you (10):"))
        assertTrue(text.contains("…and 2 more"))
        // The first 8 ids survive the cap — the lane stays actionable.
        assertTrue(text.contains("f8"))
        assertFalse(text.contains("f9"))
    }

    @Test
    fun `stale boundary is strictly older than the threshold`() {
        val now = 10_000_000L
        val exactly = listOf(
            task("edge", AgentBoardLogic.STATUS_RUNNING, createdAt = now - AgentBoardLogic.STALE_AFTER_MS)
                .copy(updatedAt = now - AgentBoardLogic.STALE_AFTER_MS),
        )
        // Exactly one hour old is NOT stale (the marker is > STALE_AFTER_MS,
        // consistent with teamSummary's own boundary).
        assertEquals("", AgentBoardLogic.needsYouSection(exactly, now = now))
    }
}
