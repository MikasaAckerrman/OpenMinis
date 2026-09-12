package com.openminis.app.data

/**
 * [T-resume-banner-false-stopped] Whether a chat may claim "Interrupted — tap
 * Resume to continue".
 *
 * ## The bug
 *
 * The user reported the screen saying the session was stopped while it was
 * demonstrably still working, right after a run of 502s.
 *
 * Two independent facts feed that banner and they can disagree:
 *
 *  - `ChatViewModel._isStreaming` — a flag owned by ONE ViewModel instance.
 *  - `SessionActivityTracker.activeSessions` — process-wide truth: the set of
 *    sessions with a live stream, maintained by the streamJob itself
 *    (`setActive` on entry, `setInactive` in its `finally`).
 *
 * `loadSession` decided "interrupted" from the local flag alone:
 * `if (lastEntry != null && !_isStreaming.value)`, then inspected the last
 * persisted row. Mid-agent-loop the last row is an assistant turn carrying a
 * `tool_use` — which is exactly the shape of an interrupted loop — so a
 * ViewModel that does not itself own the running stream concludes the session
 * died. The 502s matter because they lengthen a turn: more retries mean a wider
 * window in which the last persisted row is a mid-loop `tool_use` while the
 * stream is alive.
 *
 * The same disagreement reaches the banner gate in ChatScreen
 * (`canResume && !isStreaming && …`) and the session-list PAUSED badge, which is
 * driven off `canResume`.
 *
 * ## The rule
 *
 * A session that the PROCESS reports as streaming is never "interrupted",
 * whatever a single ViewModel's flag says. Streaming is a process-wide property;
 * the local flag is only a view of it, and when a view disagrees with the source
 * of truth, the source wins.
 *
 * Kept as a pure function so both call sites share one definition and it is
 * testable without a ViewModel, a DB or a Compose tree.
 */
object ResumeVisibilityPolicy {

    /**
     * May this chat claim to be interrupted?
     *
     * @param localStreaming the ViewModel's own `_isStreaming`.
     * @param sessionStreamingProcessWide whether [com.openminis.app.service
     *   .SessionActivityTracker] currently lists this session as active. This is
     *   the authoritative input.
     * @param lastRowLooksInterrupted result of inspecting the last persisted row
     *   (assistant turn with a pending tool_use, or a user row of tool results).
     */
    fun canClaimInterrupted(
        localStreaming: Boolean,
        sessionStreamingProcessWide: Boolean,
        lastRowLooksInterrupted: Boolean,
    ): Boolean {
        if (!lastRowLooksInterrupted) return false
        // Either signal saying "streaming" is enough to veto. They are OR-ed
        // rather than trusting only the authoritative one because the local flag
        // is set SYNCHRONOUSLY at the send/retry entry point, while setActive
        // runs inside the launched job — so for a few milliseconds the local flag
        // is the only one that knows.
        return !localStreaming && !sessionStreamingProcessWide
    }

    /**
     * Should the Resume banner be visible?
     *
     * Separate from [canClaimInterrupted] because the banner has extra
     * suppressors that are about presentation rather than truth: an inline error
     * banner on the same turn already offers Retry, and showing both invites the
     * user to retrace into the same failure.
     *
     * @param canResume the flag the ViewModel published.
     * @param localStreaming the ViewModel's own `_isStreaming`.
     * @param sessionStreamingProcessWide authoritative streaming state.
     * @param hasScreenError a screen-level error is showing.
     * @param lastAssistantHasError the last assistant turn carries its own error.
     */
    fun showResumeBanner(
        canResume: Boolean,
        localStreaming: Boolean,
        sessionStreamingProcessWide: Boolean,
        hasScreenError: Boolean,
        lastAssistantHasError: Boolean,
    ): Boolean = canResume &&
        !localStreaming &&
        !sessionStreamingProcessWide &&
        !hasScreenError &&
        !lastAssistantHasError
}
