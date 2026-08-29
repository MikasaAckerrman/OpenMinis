package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-compact-divider-count] */
class CompactDividerCountTest {

    @Test
    fun `whole history loaded uses exact bubble count`() {
        val r = CompactDividerCount.resolve(
            visibleBubblesAbove = 12,
            windowFromIndex = 0,
            markerCompactedCount = 40,
        )
        assertEquals(12, r.count)
        assertFalse(r.approximate)
    }

    @Test
    fun `windowed history falls back to marker count`() {
        // The reported defect: 2 bubbles happened to be loaded above the anchor
        // while the marker recorded 340 compacted entries.
        val r = CompactDividerCount.resolve(
            visibleBubblesAbove = 2,
            windowFromIndex = 580,
            markerCompactedCount = 340,
        )
        assertEquals(340, r.count)
        assertTrue(r.approximate)
    }

    @Test
    fun `windowed with unusable marker count keeps visible as lower bound`() {
        val r = CompactDividerCount.resolve(
            visibleBubblesAbove = 7,
            windowFromIndex = 100,
            markerCompactedCount = 0,
        )
        assertEquals(7, r.count)
        assertTrue(r.approximate)
    }

    @Test
    fun `never reports fewer than the bubbles actually visible`() {
        val r = CompactDividerCount.resolve(
            visibleBubblesAbove = 30,
            windowFromIndex = 100,
            markerCompactedCount = 5,
        )
        assertEquals(30, r.count)
        assertTrue(r.approximate)
    }

    @Test
    fun `negative inputs are clamped`() {
        val r = CompactDividerCount.resolve(
            visibleBubblesAbove = -3,
            windowFromIndex = 0,
            markerCompactedCount = -1,
        )
        assertEquals(0, r.count)
        assertFalse(r.approximate)
    }
}
