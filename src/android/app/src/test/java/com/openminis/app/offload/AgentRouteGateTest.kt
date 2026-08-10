package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRouteGateTest {

    @Test
    fun `worker never routes even when forced`() {
        // The recursion barrier. If this ever returns FORCE_GRAPH, one user
        // message can spawn an unbounded tree of graph runs.
        val d = AgentRouteGate.decide(
            forcedByUser = true,
            autoRouteEnabled = true,
            isGraphWorker = true,
        )
        assertEquals(AgentRouteGate.Intent.NORMAL_CHAT, d.intent)
    }

    @Test
    fun `button forces the graph without asking the classifier`() {
        // autoRoute is OFF here on purpose: the button must work on an install
        // that never configured a router model.
        val d = AgentRouteGate.decide(
            forcedByUser = true,
            autoRouteEnabled = false,
            isGraphWorker = false,
        )
        assertEquals(AgentRouteGate.Intent.FORCE_GRAPH, d.intent)
    }

    @Test
    fun `button skips classification even when auto-routing is on`() {
        val d = AgentRouteGate.decide(
            forcedByUser = true,
            autoRouteEnabled = true,
            isGraphWorker = false,
        )
        assertEquals(AgentRouteGate.Intent.FORCE_GRAPH, d.intent)
    }

    @Test
    fun `auto-routing alone only classifies`() {
        val d = AgentRouteGate.decide(
            forcedByUser = false,
            autoRouteEnabled = true,
            isGraphWorker = false,
        )
        assertEquals(AgentRouteGate.Intent.CLASSIFY, d.intent)
    }

    @Test
    fun `default install stays in normal chat`() {
        val d = AgentRouteGate.decide(
            forcedByUser = false,
            autoRouteEnabled = false,
            isGraphWorker = false,
        )
        assertEquals(AgentRouteGate.Intent.NORMAL_CHAT, d.intent)
    }

    @Test
    fun `forced run without a pinned graph uses the light team`() {
        assertEquals(
            BuiltinGraphs.ID_LIGHT,
            AgentRouteGate.forcedGraphId(null) { true },
        )
        assertEquals(
            BuiltinGraphs.ID_LIGHT,
            AgentRouteGate.forcedGraphId("   ") { true },
        )
    }

    @Test
    fun `pinned graph wins when it exists`() {
        assertEquals(
            BuiltinGraphs.ID_FULL,
            AgentRouteGate.forcedGraphId(BuiltinGraphs.ID_FULL) { it == BuiltinGraphs.ID_FULL },
        )
    }

    @Test
    fun `stale pinned graph falls back instead of failing the turn`() {
        assertEquals(
            BuiltinGraphs.ID_LIGHT,
            AgentRouteGate.forcedGraphId("deleted_graph") { false },
        )
    }
}
