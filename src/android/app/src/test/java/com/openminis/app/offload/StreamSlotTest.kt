package com.openminis.app.offload

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-stream-slot-claim] The run slot of a session has exactly one holder.
 *
 * The defect this guards against: the start paths read the streaming flag,
 * did their preparation, and wrote the flag afterwards. A message that
 * arrived inside that window passed the same read, so two turns ran at the
 * same time and both wrote into the same transcript.
 *
 * [H2-ownership] (2026-11-04 review) The claim now returns an ownership
 * token; only that token can release the slot. Each test uses a FRESH
 * MutableStateFlow — the registry is keyed per flow instance
 * ([N1-per-flow]), so flows are isolated by construction.
 */
class StreamSlotTest {

    @Test
    fun `only one of two contenders takes the slot`() {
        val streaming = MutableStateFlow(false)
        val first = streaming.tryClaimStreamSlot()
        assertTrue("the first contender must win", first != null)
        assertNull("the second contender must lose", streaming.tryClaimStreamSlot())
        assertTrue(streaming.value)
        streaming.releaseStreamSlot(first)
    }

    @Test
    fun `a released slot can be taken again`() {
        val streaming = MutableStateFlow(false)
        val claim = streaming.tryClaimStreamSlot()
        assertNotNull(claim)
        streaming.releaseStreamSlot(claim)
        assertFalse(streaming.value)
        assertTrue("a released slot must be free again", streaming.tryClaimStreamSlot() != null)
    }

    @Test
    fun `many contenders produce exactly one winner`() {
        val streaming = MutableStateFlow(false)
        val winners = (1..64).count { streaming.tryClaimStreamSlot() != null }
        assertTrue("exactly one winner, got $winners", winners == 1)
    }

    @Test
    fun `a stale token cannot release a newer claim`() {
        // [H2-ownership] Regression guard for the Stop-during-prepare bug:
        // turn A claims, is superseded by turn B, A's late release must be
        // a silent no-op — B's flag and ownership survive.
        val streaming = MutableStateFlow(false)
        val tokenA = streaming.tryClaimStreamSlot()
        assertNotNull(tokenA)
        // Simulate the takeover: A is force-released, B claims fresh.
        streaming.forceReleaseStreamSlot()
        val tokenB = streaming.tryClaimStreamSlot()
        assertNotNull(tokenB)
        assertTrue(streaming.value)
        // A's stale tail fires:
        streaming.releaseStreamSlot(tokenA)
        assertTrue("B's flag must survive a stale release", streaming.value)
        // B's own release works:
        streaming.releaseStreamSlot(tokenB)
        assertFalse(streaming.value)
    }

    @Test
    fun `double release of the same token is idempotent`() {
        val streaming = MutableStateFlow(false)
        val claim = streaming.tryClaimStreamSlot()
        assertNotNull(claim)
        streaming.releaseStreamSlot(claim)
        streaming.releaseStreamSlot(claim)
        assertFalse(streaming.value)
    }
}
