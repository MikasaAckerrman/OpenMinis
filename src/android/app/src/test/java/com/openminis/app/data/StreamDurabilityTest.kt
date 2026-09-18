package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-partial-turn-durability] Cadence policy for the stream journal — pure
 * logic, no I/O.
 */
class StreamDurabilityTest {

    @Test
    fun `no journal below the first-write threshold`() {
        assertFalse(StreamDurability.shouldHeartbeat(nowMs = 1_000, lastMs = 0, len = 511, lastLen = 0))
    }

    @Test
    fun `first write fires exactly at the threshold`() {
        assertTrue(StreamDurability.shouldHeartbeat(nowMs = 1_000, lastMs = 0, len = 512, lastLen = 0))
    }

    @Test
    fun `time and delta must BOTH open the gate`() {
        // time open, delta too small
        assertFalse(StreamDurability.shouldHeartbeat(nowMs = 60_000, lastMs = 0, len = 1_500, lastLen = 512))
        // delta open, time too small (len 20k → 5s tier)
        assertFalse(StreamDurability.shouldHeartbeat(nowMs = 6_000, lastMs = 3_000, len = 20_000, lastLen = 15_000))
        // both open
        assertTrue(StreamDurability.shouldHeartbeat(nowMs = 60_000, lastMs = 0, len = 20_000, lastLen = 512))
        assertTrue(StreamDurability.shouldHeartbeat(nowMs = 10_000, lastMs = 3_000, len = 20_000, lastLen = 15_000))
    }

    @Test
    fun `stale or shrinking snapshots never fire`() {
        assertFalse(StreamDurability.shouldHeartbeat(nowMs = 99_999, lastMs = 1_000, len = 2_000, lastLen = 2_000))
        assertFalse(StreamDurability.shouldHeartbeat(nowMs = 99_999, lastMs = 1_000, len = 1_000, lastLen = 2_000))
    }

    @Test
    fun `interval scales with length`() {
        assertEquals(5_000L, StreamDurability.heartbeatIntervalMs(1_000))
        assertEquals(5_000L, StreamDurability.heartbeatIntervalMs(63_999))
        assertEquals(10_000L, StreamDurability.heartbeatIntervalMs(64_000))
        assertEquals(10_000L, StreamDurability.heartbeatIntervalMs(511_999))
        assertEquals(15_000L, StreamDurability.heartbeatIntervalMs(512_000))
        assertEquals(15_000L, StreamDurability.heartbeatIntervalMs(5_000_000))
    }
}
