package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class IncrementalSpanTest {

    private val userIdx = setOf(0, 3, 6, 9)

    @Test
    fun wholeSpanFits_returnsAnchor() {
        assertEquals(4, IncrementalSpan.bound(0, 4, List(5) { 1_000 }, { it in userIdx }, budget = 100_000))
    }

    @Test
    fun budgetCutsAtLastSafeBoundary() {
        val t = List(6) { 10_000 }
        // budget 25k → cut=1 (assistant) → walk back to user index 0
        assertEquals(0, IncrementalSpan.bound(0, 5, t, { it in userIdx }, budget = 25_000))
    }

    @Test
    fun cutOnUserIndex_isUsedDirectly() {
        val t = List(5) { 9_000 }
        // budget 40k → cut=3, and index 3 is a user message → anchor 3
        assertEquals(3, IncrementalSpan.bound(0, 4, t, { it in userIdx }, budget = 40_000))
    }

    @Test
    fun noSafeBoundary_degradesToFullPass() {
        val t = List(4) { 10_000 }
        assertEquals(3, IncrementalSpan.bound(0, 3, t, { false }, budget = 15_000))
    }

    @Test
    fun emptyOrInvertedSpan_returnsAnchor() {
        assertEquals(0, IncrementalSpan.bound(0, 0, emptyList(), { true }))
        assertEquals(5, IncrementalSpan.bound(6, 5, listOf(1), { true }))
    }

    @Test
    fun negativeEstimates_areClamped() {
        val t = listOf(-5, 10_000, 10_000)
        assertEquals(2, IncrementalSpan.bound(0, 2, t, { true }, budget = 25_000))
    }
}
