package com.openminis.app.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputLimitPolicyTest {
    @Test
    fun `provider output limit reasons are normalized`() {
        assertTrue(OutputLimitPolicy.reachedLimit("length"))
        assertTrue(OutputLimitPolicy.reachedLimit("MAX_TOKENS"))
        assertTrue(OutputLimitPolicy.reachedLimit("max_output_tokens"))
    }

    @Test
    fun `clean completion is not an output limit`() {
        assertFalse(OutputLimitPolicy.reachedLimit("stop"))
        assertFalse(OutputLimitPolicy.reachedLimit("end_turn"))
        assertFalse(OutputLimitPolicy.reachedLimit(null))
    }
}
