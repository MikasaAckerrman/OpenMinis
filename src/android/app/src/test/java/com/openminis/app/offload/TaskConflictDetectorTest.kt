package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-spawn-many] The conflict rules that keep parallel agents from corrupting
 * the same file. These are the guarantees the tool description promises, so
 * they are pinned in code.
 */
class TaskConflictDetectorTest {

    private fun task(i: Int, role: String, text: String) =
        TaskConflictDetector.Task(index = i, role = role, text = text)

    @Test
    fun `file paths are extracted from task text`() {
        val paths = TaskConflictDetector.extractPaths(
            "Review /var/minis/workspace/auth/Login.kt for logic errors, then check src/util/Token.kt too",
        )
        assertTrue("/var/minis/workspace/auth/login.kt" in paths)
        assertTrue("src/util/token.kt" in paths)
    }

    @Test
    fun `generic shared roots do not count as conflicts`() {
        // Every task mentions the workspace for free — that is not a write clash.
        val a = TaskConflictDetector.extractPaths("Write the report to /var/minis/workspace")
        val b = TaskConflictDetector.extractPaths("Read config /var/minis/workspace and summarize")
        val plan = TaskConflictDetector.plan(
            listOf(task(0, "A", "Write the report to /var/minis/workspace"), task(1, "B", "Read config /var/minis/workspace and summarize")),
        )
        assertTrue(a.isEmpty() && b.isEmpty())
        assertTrue("independent tasks must be one parallel batch", plan.isFullyParallel)
        assertEquals(2, plan.batches.single().size)
    }

    @Test
    fun `same file forces serialization with a reason`() {
        val plan = TaskConflictDetector.plan(
            listOf(
                task(0, "A", "Fix the bug in /var/minis/workspace/auth/Login.kt"),
                task(1, "B", "Refactor /var/minis/workspace/auth/Login.kt to use coroutines"),
            ),
        )
        assertEquals("two batches: serialized", 2, plan.batches.size)
        assertEquals("one task per batch", 1, plan.batches[0].size)
        assertEquals(1, plan.batches[1].size)
        assertTrue(plan.conflictNotes.isNotEmpty())
        assertTrue(plan.conflictNotes.single().contains("login.kt"))
    }

    @Test
    fun `directory and file inside it conflict`() {
        val plan = TaskConflictDetector.plan(
            listOf(
                task(0, "A", "Review everything under src/android/app/auth"),
                task(1, "B", "Rewrite src/android/app/auth/Login.kt with a proper guard"),
            ),
        )
        assertEquals(2, plan.batches.size)
    }

    @Test
    fun `disjoint files stay fully parallel`() {
        val plan = TaskConflictDetector.plan(
            listOf(
                task(0, "A", "Audit /var/minis/workspace/net/Client.kt"),
                task(1, "B", "Audit /var/minis/workspace/db/Dao.kt"),
                task(2, "C", "Research how OkHttp handles timeouts, no files"),
            ),
        )
        assertTrue(plan.isFullyParallel)
        assertEquals(3, plan.batches.single().size)
        assertTrue(plan.conflictNotes.isEmpty())
    }

    @Test
    fun `one conflicting pair does not serialize the whole batch`() {
        // C is independent of both A and B — it must ride in the FIRST batch
        // alongside A, not be dragged into B's serial chain.
        val plan = TaskConflictDetector.plan(
            listOf(
                task(0, "A", "Fix /var/minis/workspace/x.kt"),
                task(1, "B", "Also fix /var/minis/workspace/x.kt but differently"),
                task(2, "C", "Summarize the API of provider Z, touches nothing"),
            ),
        )
        assertEquals(2, plan.batches.size)
        assertEquals(setOf(0, 2), plan.batches[0].map { it.index }.toSet())
        assertEquals(listOf(1), plan.batches[1].map { it.index })
    }

    @Test
    fun `empty input yields an empty plan`() {
        val plan = TaskConflictDetector.plan(emptyList())
        assertTrue(plan.batches.isEmpty())
        assertTrue(plan.isFullyParallel)
    }

    @Test
    fun `bare prose words are not treated as paths`() {
        val paths = TaskConflictDetector.extractPaths(
            "Review the login flow: check the auth. Then look at 5 more things and report.",
        )
        // "auth." style prose must not fabricate a path token.
        assertTrue(paths.none { it.contains("auth") })
    }
}
