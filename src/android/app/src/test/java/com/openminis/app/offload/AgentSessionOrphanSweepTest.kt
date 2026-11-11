package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [B49-orphan-sweep] The sweep's decision: a dir goes ONLY when its sid is
 * absent from the sessions table AND its mtime is past the creation-window
 * guard. Live work and brand-new spawns are untouchable by construction —
 * these pins are the reviewer's APPROVE conditions made executable.
 */
class AgentSessionOrphanSweepTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(name: String, ageMs: Long, nowMs: Long) =
        tmp.newFolder(name).apply { setLastModified(nowMs - ageMs) }

    @Test
    fun `an old sid-less dir is an orphan`() {
        val now = 1_700_000_000_000L
        val orphan = dir("dead-session", ageMs = 60L * 60 * 1000, nowMs = now)
        val picked = AgentSessionManager.orphanedSessionDirs(
            listOf(orphan), liveIds = setOf("alive-1"), nowMs = now,
        )
        assertEquals(listOf("dead-session"), picked.map { it.name })
    }

    @Test
    fun `a live sid is never touched`() {
        val now = 1_700_000_000_000L
        val live = dir("alive-1", ageMs = 60L * 60 * 1000, nowMs = now)
        val picked = AgentSessionManager.orphanedSessionDirs(
            listOf(live), liveIds = setOf("alive-1"), nowMs = now,
        )
        assertEquals(emptyList<String>(), picked.map { it.name })
    }

    @Test
    fun `a young sid-less dir is protected - the creation-window race`() {
        // A spawn creates its dir tree BEFORE the sessions row commits: a
        // just-created live dir must never read as orphaned.
        val now = 1_700_000_000_000L
        val fresh = dir("just-spawned", ageMs = 60_000L, nowMs = now)
        val picked = AgentSessionManager.orphanedSessionDirs(
            listOf(fresh), liveIds = emptySet(), nowMs = now,
        )
        assertEquals(emptyList<String>(), picked.map { it.name })
    }

    @Test
    fun `the full vc163 shape - 1550 orphans and one live session survive intact`() {
        val now = 1_700_000_000_000L
        val dirs = (1..1550).map { dir("stale-$it", ageMs = 3_600_000L, nowMs = now) } +
            dir("alive-1", ageMs = 3_600_000L, nowMs = now)
        val picked = AgentSessionManager.orphanedSessionDirs(
            dirs, liveIds = setOf("alive-1"), nowMs = now,
        )
        assertEquals(1550, picked.size)
        assertEquals(emptyList<String>(), picked.map { it.name }.filter { it == "alive-1" })
    }
}
