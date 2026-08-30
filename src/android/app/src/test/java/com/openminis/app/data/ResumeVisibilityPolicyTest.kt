package com.openminis.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-resume-banner-false-stopped] The chat claimed the session was stopped while
 * it was still working, right after a run of 502s.
 */
class ResumeVisibilityPolicyTest {

    // ── canClaimInterrupted ─────────────────────────────────────────────────

    @Test
    fun `a genuinely dead session with a pending tool_use is interrupted`() {
        assertTrue(
            ResumeVisibilityPolicy.canClaimInterrupted(
                localStreaming = false,
                sessionStreamingProcessWide = false,
                lastRowLooksInterrupted = true,
            ),
        )
    }

    @Test
    fun `the process-wide flag vetoes the local one — the reported bug`() {
        // The ViewModel reading the DB is not the one that owns the running
        // stream, so its local flag is false. Mid-agent-loop the last persisted
        // row IS an assistant turn with a pending tool_use, which looks exactly
        // like an interrupted loop. Without the veto the chat declares a live
        // session stopped.
        assertFalse(
            ResumeVisibilityPolicy.canClaimInterrupted(
                localStreaming = false,
                sessionStreamingProcessWide = true,
                lastRowLooksInterrupted = true,
            ),
        )
    }

    @Test
    fun `the local flag also vetoes, covering the setActive gap`() {
        // _isStreaming is claimed synchronously at the send/retry entry point,
        // while setActive runs inside the launched job. In that window the local
        // flag is the ONLY signal that knows a turn started.
        assertFalse(
            ResumeVisibilityPolicy.canClaimInterrupted(
                localStreaming = true,
                sessionStreamingProcessWide = false,
                lastRowLooksInterrupted = true,
            ),
        )
    }

    @Test
    fun `a last row that does not look interrupted never claims interrupted`() {
        // No amount of not-streaming makes a completed turn resumable.
        assertFalse(
            ResumeVisibilityPolicy.canClaimInterrupted(
                localStreaming = false,
                sessionStreamingProcessWide = false,
                lastRowLooksInterrupted = false,
            ),
        )
    }

    // ── showResumeBanner ────────────────────────────────────────────────────

    @Test
    fun `banner shows for an interrupted, idle, error-free chat`() {
        assertTrue(
            ResumeVisibilityPolicy.showResumeBanner(
                canResume = true,
                localStreaming = false,
                sessionStreamingProcessWide = false,
                hasScreenError = false,
                lastAssistantHasError = false,
            ),
        )
    }

    @Test
    fun `banner is hidden while the session streams process-wide`() {
        assertFalse(
            ResumeVisibilityPolicy.showResumeBanner(
                canResume = true,
                localStreaming = false,
                sessionStreamingProcessWide = true,
                hasScreenError = false,
                lastAssistantHasError = false,
            ),
        )
    }

    @Test
    fun `banner is hidden when an error banner already offers Retry`() {
        // Showing both invites the user to retrace into the same failure.
        assertFalse(
            ResumeVisibilityPolicy.showResumeBanner(
                canResume = true,
                localStreaming = false,
                sessionStreamingProcessWide = false,
                hasScreenError = true,
                lastAssistantHasError = false,
            ),
        )
        assertFalse(
            ResumeVisibilityPolicy.showResumeBanner(
                canResume = true,
                localStreaming = false,
                sessionStreamingProcessWide = false,
                hasScreenError = false,
                lastAssistantHasError = true,
            ),
        )
    }

    @Test
    fun `banner needs canResume — it is not derived from the streaming state`() {
        assertFalse(
            ResumeVisibilityPolicy.showResumeBanner(
                canResume = false,
                localStreaming = false,
                sessionStreamingProcessWide = false,
                hasScreenError = false,
                lastAssistantHasError = false,
            ),
        )
    }

    @Test
    fun `the two entry points agree on the streaming vetoes`() {
        // Same veto semantics in both, so the badge and the banner cannot
        // disagree about whether a session is interrupted.
        for (local in listOf(false, true)) {
            for (process in listOf(false, true)) {
                val claim = ResumeVisibilityPolicy.canClaimInterrupted(
                    localStreaming = local,
                    sessionStreamingProcessWide = process,
                    lastRowLooksInterrupted = true,
                )
                val banner = ResumeVisibilityPolicy.showResumeBanner(
                    canResume = true,
                    localStreaming = local,
                    sessionStreamingProcessWide = process,
                    hasScreenError = false,
                    lastAssistantHasError = false,
                )
                assertTrue(
                    "local=$local process=$process claim=$claim banner=$banner",
                    claim == banner,
                )
            }
        }
    }
}
