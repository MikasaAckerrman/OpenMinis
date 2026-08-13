package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-context-pressure-blind] Reported symptom: "the session still doesn't work,
 * maybe it tried to read the whole session at once, even the part it didn't
 * compact". Two independent causes, both tested here and in
 * [HistoryTailBudgetTest].
 *
 * This half: every automatic gate keyed off a counter that only a SUCCESSFUL
 * response populates. On a failing session it stays 0, so "pressure unknown"
 * won every decision and nothing ever fired.
 */
class ContextPressureTest {

    @Test
    fun `provider usage wins when present`() {
        // It comes from the real tokeniser, so it beats our char estimate.
        assertEquals(150_000, ContextPressure.resolve(usageTokens = 150_000, estimatedTokens = 130_000))
    }

    @Test
    fun `falls back to the estimate when no usage was ever reported`() {
        // This is the failing-session case: no successful turn → counter 0.
        assertEquals(130_000, ContextPressure.resolve(usageTokens = 0, estimatedTokens = 130_000))
    }

    @Test
    fun `zero only when both signals are absent`() {
        assertEquals(0, ContextPressure.resolve(0, 0))
    }

    @Test
    fun `isEstimated flags the fallback path only`() {
        assertTrue(ContextPressure.isEstimated(0, 5_000))
        assertFalse(ContextPressure.isEstimated(9_000, 5_000))
        assertFalse(ContextPressure.isEstimated(0, 0))
    }

    @Test
    fun `maintenance now acts on a failing session instead of idling`() {
        // Regression guard for the actual bug: before the fix this session
        // reported tokens=0 and got LIGHT forever, so the oversized history
        // kept being sent. With the estimate it escalates.
        val window = 200_000
        val estimated = 180_000
        val blind = ContextMaintenance.decide(
            userTurnsSinceFull = 9,
            contextTokens = 0,                    // what the code used to see
            contextWindow = window,
            compactSupported = true,
            isCompacting = false,
            lastFullAtMs = 0L,
            nowMs = 10_000_000L,
        )
        val sighted = ContextMaintenance.decide(
            userTurnsSinceFull = 9,
            contextTokens = ContextPressure.resolve(0, estimated),
            contextWindow = window,
            compactSupported = true,
            isCompacting = false,
            lastFullAtMs = 0L,
            nowMs = 10_000_000L,
        )
        assertEquals(ContextMaintenance.Action.LIGHT, blind)
        assertEquals(ContextMaintenance.Action.RESCUE, sighted)
    }

    @Test
    fun `rescue hint now fires on a vague error in a big failing session`() {
        val msg = "no response from server (30s) — check network/proxy"
        val window = 200_000
        assertFalse(
            "blind: 0/200000 reads as a small session",
            RescueAdvisor.shouldSuggestRescue(msg, contextTokens = 0, contextWindow = window),
        )
        assertTrue(
            RescueAdvisor.shouldSuggestRescue(
                msg,
                contextTokens = ContextPressure.resolve(0, 180_000),
                contextWindow = window,
            ),
        )
    }
}

/**
 * [T-degraded-history-budget] The other half of the same report. When a compact
 * marker's anchor could not be resolved, the code "degraded to full history" —
 * dropping the summary and sending everything, including the folded-away part.
 * On the oversized session that reaches this path, that degradation IS the
 * failure.
 */
class HistoryTailBudgetTest {

    private fun user(tokens: Int, clean: Boolean = true) =
        HistoryTailBudget.Candidate(isCleanUserTurn = clean, tokens = tokens)

    private fun assistant(tokens: Int) =
        HistoryTailBudget.Candidate(isCleanUserTurn = false, tokens = tokens)

    @Test
    fun `everything fits leaves the history untouched`() {
        val c = listOf(user(100), assistant(200), user(100), assistant(200))
        assertEquals(0, HistoryTailBudget.startIndex(c, budgetTokens = 10_000))
    }

    @Test
    fun `oversized history is trimmed to a recent tail`() {
        // 20 rounds of 10K each = 200K; budget 50K keeps roughly the last 5.
        val c = (1..20).flatMap { listOf(user(2_000), assistant(8_000)) }
        val start = HistoryTailBudget.startIndex(c, budgetTokens = 50_000)
        assertTrue("start=$start", start > 0)
        val kept = c.drop(start).sumOf { it.tokens }
        assertTrue("kept=$kept", kept <= 50_000)
    }

    @Test
    fun `the cut always lands on a clean user turn`() {
        // Cutting mid-round would orphan a tool_result and turn a size error
        // into a protocol rejection.
        val c = (1..20).flatMap { listOf(user(2_000), assistant(8_000)) }
        val start = HistoryTailBudget.startIndex(c, budgetTokens = 50_000)
        assertTrue(c[start].isCleanUserTurn)
    }

    @Test
    fun `a user turn carrying tool results is not a valid cut point`() {
        // Tool results ride on USER-role messages; starting there loses the
        // assistant tool_use that produced them.
        val c = listOf(
            user(1_000),
            assistant(40_000),
            user(40_000, clean = false),   // tool results
            assistant(1_000),
        )
        val start = HistoryTailBudget.startIndex(c, budgetTokens = 45_000)
        assertTrue("start=$start must be 0 or a clean user turn", start == 0 || c[start].isCleanUserTurn)
    }

    @Test
    fun `no safe cut means send everything rather than corrupt the history`() {
        // One giant unsplittable round: trimming it here would break pairing,
        // and the caller has offload/rescue for this case.
        val c = listOf(user(1_000), assistant(500_000))
        assertEquals(0, HistoryTailBudget.startIndex(c, budgetTokens = 10_000))
    }

    @Test
    fun `empty history and zero budget are handled`() {
        assertEquals(0, HistoryTailBudget.startIndex(emptyList(), 10_000))
        assertEquals(0, HistoryTailBudget.startIndex(listOf(user(10)), 0))
    }

    @Test
    fun `tail budget reserves room for the summary and an answer`() {
        val b = HistoryTailBudget.tailBudget(contextWindow = 200_000, summaryTokens = 3_000)
        assertEquals(177_000, b)
        assertTrue(b < 200_000)
    }

    @Test
    fun `tail budget never goes non-positive on a small window`() {
        val b = HistoryTailBudget.tailBudget(contextWindow = 8_000, summaryTokens = 3_000)
        assertTrue("was $b", b >= 4_000)
    }
}
