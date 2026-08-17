package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-protected-tail] Verifies the invariant "the last N user turns are never
 * compacted". These tests fail (RED) against the old behaviour of anchoring at
 * lastIndex and pass (GREEN) with [ProtectedTail].
 */
class ProtectedTailTest {

    private fun u(hasDb: Boolean = true) = ProtectedTail.Entry(isUser = true, hasDbId = hasDb)
    private fun a(hasDb: Boolean = true) = ProtectedTail.Entry(isUser = false, hasDbId = hasDb)

    /** Build a simple alternating user/assistant history of `userTurns` turns. */
    private fun convo(userTurns: Int): List<ProtectedTail.Entry> =
        buildList { repeat(userTurns) { add(u()); add(a()) } }

    @Test
    fun `protects the last 6 user turns — anchor sits before them`() {
        // 10 user turns, alternating u/a → 20 entries (turn k at index 2k).
        // Protecting 6 means the 6th-from-last user turn opens the protected
        // region: turns 4..9 at indices 8,10,12,14,16,18. protectStart = 8
        // (turn #4). Anchor = 8 - 1 = 7 (the assistant reply of turn #3).
        val entries = convo(10)
        val anchor = ProtectedTail.anchorIndex(entries, protectedUserTurns = 6)
        assertEquals(7, anchor)
        // Everything after the anchor (indices 8..19) is the protected tail:
        // that must be exactly 6 user turns.
        val protectedUserCount = (anchor + 1 until entries.size).count { entries[it].isUser }
        assertEquals(6, protectedUserCount)
    }

    @Test
    fun `session with fewer user turns than the tail is never compacted`() {
        // 4 user turns < 6 protected → nothing older to fold.
        assertEquals(-1, ProtectedTail.anchorIndex(convo(4), protectedUserTurns = 6))
    }

    @Test
    fun `exactly 6 user turns — still all protected`() {
        assertEquals(-1, ProtectedTail.anchorIndex(convo(6), protectedUserTurns = 6))
    }

    @Test
    fun `7 user turns — only the oldest single turn is compactable`() {
        // 7 turns → 14 entries. Protect 6 → protectStart = 6th-from-last user =
        // index 2 (turn #1). Anchor = 1 (the assistant reply of turn #0).
        val anchor = ProtectedTail.anchorIndex(convo(7), protectedUserTurns = 6)
        assertEquals(1, anchor)
    }

    @Test
    fun `anchor walks back to a persisted entry`() {
        // 8 user turns; make the natural anchor (index just before protected
        // region) non-persisted so the walk-back must skip it.
        val entries = convo(8).toMutableList()
        // Protect 6 → protectStart at index (8-6)=turn#2 → entry 4. Anchor=3.
        // Mark entry 3 as non-persisted → must fall back to entry 2.
        entries[3] = a(hasDb = false)
        val anchor = ProtectedTail.anchorIndex(entries, protectedUserTurns = 6)
        assertEquals(2, anchor)
    }

    @Test
    fun `protection disabled falls back to last persisted entry`() {
        val entries = convo(3) // 6 entries, last index 5 (assistant, persisted)
        assertEquals(5, ProtectedTail.anchorIndex(entries, protectedUserTurns = 0))
    }

    @Test
    fun `anchor ceiling clamps a manual compact-before gesture`() {
        // 10 user turns; protected-tail anchor would be 9, but the user asked
        // to compact only up to index 4 → clamp to 4.
        val entries = convo(10)
        val anchor = ProtectedTail.anchorIndex(entries, protectedUserTurns = 6, anchorCeiling = 4)
        assertEquals(4, anchor)
    }

    @Test
    fun `empty history returns no anchor`() {
        assertEquals(-1, ProtectedTail.anchorIndex(emptyList(), protectedUserTurns = 6))
    }
}
