package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRouteGateTest {




    @Test
    fun `auto-routing alone only classifies`() {
        val d = AgentRouteGate.decide(
            autoRouteEnabled = true,
            isGraphWorker = false,
        )
        assertEquals(AgentRouteGate.Intent.CLASSIFY, d.intent)
    }

    @Test
    fun `default install stays in normal chat`() {
        val d = AgentRouteGate.decide(
            autoRouteEnabled = false,
            isGraphWorker = false,
        )
        assertEquals(AgentRouteGate.Intent.NORMAL_CHAT, d.intent)
    }



}
