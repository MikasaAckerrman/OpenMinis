package com.openminis.app.offload

import com.openminis.app.data.model.AgentRole
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B36-stall-feed] The activity seam the spawn_many watchdog reads: every
 * filed event (node/tool lifecycle) and every external pulse ([touch] —
 * the stream heartbeat's cadence) must refresh the run's last-activity
 * stamp, and a cleared run must forget it (a stale stamp would let a NEW
 * run on a recycled task id be judged by a DEAD run's silence).
 */
class AgentRunProgressActivityTest {

    @Test
    fun `a run with no filed activity reads as alive (null)`() {
        AgentRunProgress.clearAll()
        try {
            AgentRunProgress.begin("t1", "graph")
            assertNull(AgentRunProgress.idleMs("t1"))
        } finally {
            AgentRunProgress.clearAll()
        }
    }

    @Test
    fun `touch refreshes the stamp`() {
        AgentRunProgress.clearAll()
        try {
            AgentRunProgress.begin("t1", "graph")
            AgentRunProgress.touch("t1")
            val idle = AgentRunProgress.idleMs("t1")
            assertTrue("touched run must have a known age", idle != null)
            assertTrue("touched run must read fresh, was $idle", idle!! < 5_000L)
        } finally {
            AgentRunProgress.clearAll()
        }
    }

    @Test
    fun `a registry event doubles as a liveness pulse`() {
        AgentRunProgress.clearAll()
        try {
            AgentRunProgress.begin("t1", "graph")
            AgentRunProgress.nodeStarted(
                taskId = "t1",
                runtimeId = "n1",
                role = AgentRole.EXPLORE,
                label = "explore",
                replicaInfo = null,
                model = "m",
            )
            val idle = AgentRunProgress.idleMs("t1")
            assertTrue("nodeStarted must bump activity", idle != null && idle!! < 5_000L)
        } finally {
            AgentRunProgress.clearAll()
        }
    }

    @Test
    fun `clear forgets the stamp - no dead-run silence for a recycled id`() {
        AgentRunProgress.clearAll()
        try {
            AgentRunProgress.begin("t1", "graph")
            AgentRunProgress.touch("t1")
            AgentRunProgress.clear("t1")
            assertNull(AgentRunProgress.idleMs("t1"))
        } finally {
            AgentRunProgress.clearAll()
        }
    }

    @Test
    fun `touch for an unknown run is a cheap no-op`() {
        AgentRunProgress.clearAll()
        try {
            AgentRunProgress.touch("ghost")
            assertNull(AgentRunProgress.idleMs("ghost"))
        } finally {
            AgentRunProgress.clearAll()
        }
    }
}
