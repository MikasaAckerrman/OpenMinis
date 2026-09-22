package com.openminis.app.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-headless-retry-honest-signal] Guards the terminal-status decision for a
 * waited headless retry. The bug this locks down: the harness used to report
 * "Completed" (with a predicted deletedMessageCount and a stale responseText)
 * even when the retry was a silent no-op or when the tail was truncated but no
 * new answer was regenerated. The status must now distinguish those cases.
 */
class HeadlessRetryStatusTest {

    private val runner = HeadlessChatRunner

    @Test
    fun `regenerated answer after streaming settled is Completed`() {
        assertEquals("Completed", runner.decideRetryStatus(finished = true, regenerated = true))
    }

    @Test
    fun `settled with no newer assistant row is TruncatedNoRegen`() {
        // The exact live-DB case observed on the sandbox: retry archived the
        // tail (21 rows) but headless provider-resolve aborted the stream, so
        // the newest assistant row was still the pre-anchor one — NOT a new
        // answer. Reporting this as Completed is the lie we are removing.
        assertEquals("TruncatedNoRegen", runner.decideRetryStatus(finished = true, regenerated = false))
    }

    @Test
    fun `not finished before the wait timeout is Timeout regardless of regeneration`() {
        assertEquals("Timeout", runner.decideRetryStatus(finished = false, regenerated = false))
        assertEquals("Timeout", runner.decideRetryStatus(finished = false, regenerated = true))
    }

    // [T-headless-send-gate] prompt()'s terminal status, same honesty
    // contract. The dropped-send case below was the live failure mode of
    // spawn-6b317aab: a sendMessage guard refused the send (send gate not
    // open yet), isStreaming never flipped true, and the old code reported
    // "Completed" with a null response — the graph runner treated that as a
    // non-retryable result and failed the node 20 ms in with no evidence.
    @Test
    fun `prompt status is Completed when streaming started and finished`() {
        assertEquals(
            "Completed",
            runner.decidePromptStatus(finished = true, started = true, hasResponse = true),
        )
    }

    @Test
    fun `prompt status is Completed when the stream finished before we looked`() {
        assertEquals(
            "Completed",
            runner.decidePromptStatus(finished = true, started = false, hasResponse = true),
        )
    }

    @Test
    fun `prompt status is Error when the send never started streaming`() {
        assertEquals(
            "Error",
            runner.decidePromptStatus(finished = true, started = false, hasResponse = false),
        )
    }

    @Test
    fun `prompt status is Timeout when the wait timed out`() {
        assertEquals(
            "Timeout",
            runner.decidePromptStatus(finished = false, started = true, hasResponse = false),
        )
    }
}
