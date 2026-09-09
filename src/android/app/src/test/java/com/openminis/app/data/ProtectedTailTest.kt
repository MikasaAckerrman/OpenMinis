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

    // [T-compact-tail-vs-window] The tail must scale with the model's window:
    // a fixed 6-turn tail on a small-context model outweighs the window and
    // makes compaction mathematically unable to fit the session — the
    // "compacted but the request still doesn't fit" trap after switching
    // from a large-context model to a small one.
    @Test
    fun `protected tail scales down with the context window`() {
        assertEquals(6, ProtectedTail.protectedTurnsForWindow(128_000))
        assertEquals(6, ProtectedTail.protectedTurnsForWindow(200_000))
        assertEquals(5, ProtectedTail.protectedTurnsForWindow(64_000))
        assertEquals(5, ProtectedTail.protectedTurnsForWindow(127_999))
        assertEquals(4, ProtectedTail.protectedTurnsForWindow(32_000))
        assertEquals(4, ProtectedTail.protectedTurnsForWindow(63_999))
        assertEquals(3, ProtectedTail.protectedTurnsForWindow(16_000))
        assertEquals(3, ProtectedTail.protectedTurnsForWindow(31_999))
        assertEquals(2, ProtectedTail.protectedTurnsForWindow(15_999))
        assertEquals(2, ProtectedTail.protectedTurnsForWindow(8_000))
        assertEquals(2, ProtectedTail.protectedTurnsForWindow(4_096))
    }

    @Test
    fun `default equals the large-window tier`() {
        // The default constant stays meaningful: it is the value used when no
        // window is known, and it must equal the ≥128k tier so an unknown
        // window behaves like a generous one (matches the pre-fix constant 6).
        assertEquals(
            ProtectedTail.DEFAULT_PROTECTED_USER_TURNS,
            ProtectedTail.protectedTurnsForWindow(128_000),
        )
    }

    @Test
    fun `small window compacts more - anchor moves below the fresh turns`() {
        // 8 user turns (16 entries, user at even indices); on a 128k window
        // the last 6 user turns stay verbatim (protectStart=4 → anchor=3).
        // On a 16k window only the last 3 stay verbatim (protectStart=10 →
        // anchor=9), so compaction covers MORE of the settled history —
        // exactly the behaviour a small-context model needs to fit.
        val entries = convo(8)
        val bigWindowAnchor = ProtectedTail.anchorIndex(
            entries,
            protectedUserTurns = ProtectedTail.protectedTurnsForWindow(128_000),
        )
        val smallWindowAnchor = ProtectedTail.anchorIndex(
            entries,
            protectedUserTurns = ProtectedTail.protectedTurnsForWindow(16_000),
        )
        assertEquals(3, bigWindowAnchor)
        assertEquals(9, smallWindowAnchor)
        // The invariant "smaller window → anchor lower-or-equal (compacts at
        // least as much)" holds for every tier pair by construction; pinned
        // here with the extreme pair.
        org.junit.Assert.assertTrue(smallWindowAnchor >= bigWindowAnchor)
    }

    // [T-tail-token-budget] The tail must ALSO be bounded in tokens: turn
    // counts cannot see a fat tool-heavy turn that outweighs the whole
    // window by itself. Budget = window/4 clamped to [1024, 32768].
    @Test
    fun `tail budget scales proportionally with the window`() {
        assertEquals(1_024, ProtectedTail.tailBudgetTokens(4_096))    // clamped up
        assertEquals(2_000, ProtectedTail.tailBudgetTokens(8_000))
        assertEquals(4_000, ProtectedTail.tailBudgetTokens(16_000))
        assertEquals(8_000, ProtectedTail.tailBudgetTokens(32_000))
        assertEquals(32_000, ProtectedTail.tailBudgetTokens(128_000))
        assertEquals(32_768, ProtectedTail.tailBudgetTokens(131_072)) // cap kicks in
        assertEquals(32_768, ProtectedTail.tailBudgetTokens(1_000_000))
    }

    @Test
    fun `token budget stops the tail walk earlier than the turn count`() {
        // 10 turns, each turn ~300 tokens (u+a at 150 each). Budget 500 lets
        // only the LAST turn's slice fit: walking back, after accepting the
        // newest turn (u@18, slice [18..19] = 300 tokens) the next candidate
        // u@16 would push the slice to 600 > 500 → the tail stays at ONE
        // turn and turn #8 opens the compactable region instead.
        val entries = buildList {
            repeat(10) {
                add(ProtectedTail.Entry(isUser = true, hasDbId = true, tokens = 150))
                add(ProtectedTail.Entry(isUser = false, hasDbId = true, tokens = 150))
            }
        }
        val anchor = ProtectedTail.anchorIndex(
            entries,
            protectedUserTurns = 6,          // count would allow 6 turns
            tokenBudget = 500,               // tokens allow only 1
        )
        // Tail = [18..19] (one turn); anchor = the entry just before it.
        assertEquals(17, anchor)
        val protectedUserCount = (anchor + 1 until entries.size).count { entries[it].isUser }
        assertEquals(1, protectedUserCount)
    }

    @Test
    fun `first user turn is protected even when it alone exceeds the budget`() {
        // The newest turn is FAT (5_000 tokens) and the budget is tiny (1_024):
        // the min-one-turn guarantee must keep it verbatim — an empty tail
        // would blind the model to everything after the summary.
        val entries = buildList {
            repeat(4) {
                add(ProtectedTail.Entry(isUser = true, hasDbId = true, tokens = 5_000))
                add(ProtectedTail.Entry(isUser = false, hasDbId = true, tokens = 1_000))
            }
        }
        val anchor = ProtectedTail.anchorIndex(
            entries,
            protectedUserTurns = 6,
            tokenBudget = 1_024,
        )
        // protectStart = user of the LAST turn (index 6), anchor = 5.
        assertEquals(5, anchor)
        val protectedUserCount = (anchor + 1 until entries.size).count { entries[it].isUser }
        assertEquals(1, protectedUserCount)
    }

    @Test
    fun `token budget default keeps legacy count-only behaviour`() {
        // Default Int.MAX_VALUE must reproduce the exact pre-budget anchor
        // for the same entries — old sessions and count-only callers are
        // unaffected.
        val entries = buildList {
            repeat(10) {
                add(ProtectedTail.Entry(isUser = true, hasDbId = true, tokens = 99_999))
                add(ProtectedTail.Entry(isUser = false, hasDbId = true, tokens = 99_999))
            }
        }
        assertEquals(
            ProtectedTail.anchorIndex(entries, protectedUserTurns = 6, tokenBudget = Int.MAX_VALUE),
            ProtectedTail.anchorIndex(entries, protectedUserTurns = 6),
        )
        assertEquals(7, ProtectedTail.anchorIndex(entries, protectedUserTurns = 6))
    }

    @Test
    fun `cheap turns still get the full count when budget is generous`() {
        // 10 turns × 20 tokens; budget 32_768 (the ≥128k tier) — the count
        // limit (6) must bind, not the token budget.
        val entries = buildList {
            repeat(10) {
                add(ProtectedTail.Entry(isUser = true, hasDbId = true, tokens = 10))
                add(ProtectedTail.Entry(isUser = false, hasDbId = true, tokens = 10))
            }
        }
        val anchor = ProtectedTail.anchorIndex(
            entries,
            protectedUserTurns = 6,
            tokenBudget = ProtectedTail.tailBudgetTokens(128_000),
        )
        assertEquals(7, anchor)
        val protectedUserCount = (anchor + 1 until entries.size).count { entries[it].isUser }
        assertEquals(6, protectedUserCount)
    }
}
