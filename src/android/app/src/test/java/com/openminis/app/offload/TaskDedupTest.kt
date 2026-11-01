package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-agent-dedup] The mechanical duplicate gate: two agents must never run
 * the same task in parallel. Pure JVM — the threshold semantics are pinned
 * here (reworded-same-work is a duplicate; a distinct focus is not; a
 * different file/topic is not).
 */
class TaskDedupTest {

    @Test
    fun `reworded same task is a duplicate`() {
        val sim = TaskDedup.similarity(
            "Review the auth module for logic errors",
            "review auth module for logic errors",
        )
        assertTrue("reworded duplicate must exceed the threshold: $sim", sim >= TaskDedup.DUPLICATE_THRESHOLD)
        assertNotNull(TaskDedup.findDuplicate("Review the auth module for logic errors", mapOf("spawn-ab12" to "review auth module for logic errors")))
    }

    @Test
    fun `distinct focus is not a duplicate`() {
        val sim = TaskDedup.similarity(
            "Review the auth module for security issues",
            "Review the auth module for logic errors",
        )
        assertTrue("a distinct focus must stay below the threshold: $sim", sim < TaskDedup.DUPLICATE_THRESHOLD)
        assertNull(TaskDedup.findDuplicate("Review the auth module for security issues", mapOf("spawn-ab12" to "Review the auth module for logic errors")))
    }

    @Test
    fun `different files are not duplicates`() {
        assertNull(
            TaskDedup.findDuplicate(
                "Implement retry backoff for the network layer",
                mapOf("spawn-xy98" to "Implement the settings screen layout"),
            ),
        )
    }

    @Test
    fun `closest duplicate wins when several actives are similar`() {
        val dup = TaskDedup.findDuplicate(
            "review the auth module for logic errors",
            mapOf(
                "spawn-1" to "review the auth module for logic errors and edge cases",
                "spawn-2" to "review the auth module for logic errors",
            ),
        )
        assertNotNull(dup)
        assertEquals("spawn-2", dup!!.first)
        assertEquals(1.0, dup.second, 0.0001)
    }

    @Test
    fun `empty inputs never match`() {
        assertNull(TaskDedup.findDuplicate("", mapOf("spawn-1" to "review the auth module")))
        assertNull(TaskDedup.findDuplicate("review the auth module", mapOf("spawn-1" to "")))
        assertNull(TaskDedup.findDuplicate("   ", mapOf("spawn-1" to "   ")))
    }
}
