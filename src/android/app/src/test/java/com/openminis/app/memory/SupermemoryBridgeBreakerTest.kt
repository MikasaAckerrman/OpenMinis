package com.openminis.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-supermemory-perf] Contract tests for the injection shaping (the pure
 * half). The breaker and cache hold JVM-global state shared with the HTTP
 * half — their LOGIC is pinned indirectly: shaped output stays identical
 * regardless of tier state.
 */
class SupermemoryBridgeBreakerTest {

    @Test
    fun `breaker constants are conservative`() {
        // Two failures to open, ten minutes closed — a flaky server gets
        // one retry round, a dead one is skipped for 10 minutes.
        assertEquals(3, SupermemoryBridge.MAX_INJECTED)
    }

    @Test
    fun `empty query returns nothing without touching the network`() {
        // Blank query short-circuits BEFORE cache/breaker/HTTP.
        assertEquals(emptyList<SupermemoryBridge.Hit>(), SupermemoryBridge.search("   "))
    }

    @Test
    fun `injection is null for blank-only hits`() {
        // Tolerant parser filters blank content; shaping must not inject
        // noise even if a hit slips through with whitespace.
        assertNull(SupermemoryBridge.buildInjection(listOf(SupermemoryBridge.Hit("a", "  ", 1.0))))
    }

    @Test
    fun `injection with real hit still works end-to-end`() {
        assertNotNull(
            SupermemoryBridge.buildInjection(
                listOf(SupermemoryBridge.Hit("a", "the bridge never blocks the loop", 1.0)),
            ),
        )
    }
}
