package com.openminis.app.offload

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-stream-slot-claim] The run slot of a session has exactly one holder.
 *
 * The defect this guards against: the start paths read the streaming flag,
 * did their preparation, and wrote the flag afterwards. A message that
 * arrived inside that window passed the same read, so two turns ran at the
 * same time and both wrote into the same transcript.
 */
class StreamSlotTest {

    @Test
    fun `only one of two contenders takes the slot`() {
        val streaming = MutableStateFlow(false)
        assertTrue("the first contender must win", streaming.tryClaimStreamSlot())
        assertFalse("the second contender must lose", streaming.tryClaimStreamSlot())
        assertTrue(streaming.value)
    }

    @Test
    fun `a released slot can be taken again`() {
        val streaming = MutableStateFlow(false)
        assertTrue(streaming.tryClaimStreamSlot())
        streaming.releaseStreamSlot()
        assertFalse(streaming.value)
        assertTrue("a released slot must be free again", streaming.tryClaimStreamSlot())
    }

    @Test
    fun `many contenders produce exactly one winner`() {
        val streaming = MutableStateFlow(false)
        val winners = (1..64).count { streaming.tryClaimStreamSlot() }
        assertTrue("exactly one winner, got $winners", winners == 1)
    }
}
