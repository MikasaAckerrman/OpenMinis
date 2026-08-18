package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextUsageAttributionTest {

    @Test
    fun `usage belongs only to the model and effective window that reported it`() {
        val usage = ContextUsageAttribution.capture(
            tokens = 130_000,
            modelId = "large-model",
            effectiveWindow = 200_000,
        )

        assertEquals(130_000, usage.tokensFor("large-model", 200_000))
        assertEquals(0, usage.tokensFor("small-model", 200_000))
        assertEquals(0, usage.tokensFor("large-model", 100_000))
    }
}
