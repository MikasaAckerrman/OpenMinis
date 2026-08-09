package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentQuotaBackoffTest {
    @Test
    fun `halves 128k down to 4k then stops`() {
        val caps = mutableListOf<Int>()
        var cap = 128_000
        while (true) {
            val next = AgentQuotaBackoff.nextCap(cap) ?: break
            caps += next
            cap = next
        }
        assertEquals(listOf(64_000, 32_000, 16_000, 8_000, 4_096), caps)
        assertNull(AgentQuotaBackoff.nextCap(4_096))
    }

    @Test
    fun `default graph cap retries 8k then 4k`() {
        assertEquals(8_192, AgentQuotaBackoff.nextCap(16_384))
        assertEquals(4_096, AgentQuotaBackoff.nextCap(8_192))
    }
}
