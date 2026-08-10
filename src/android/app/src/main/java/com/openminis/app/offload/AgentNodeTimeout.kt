package com.openminis.app.offload

import com.openminis.app.data.model.AgentRole

/**
 * How long one node's turn may take before it is treated as a timeout.
 *
 * Why per role instead of one graph-wide number: the graph's `defaultTimeoutMs`
 * is 120 s, inherited from an ordinary chat turn, and that is simply wrong for
 * planning. Measured on device (run f0949263, 2026-08-10): the ORCHESTRATOR node
 * on claude-opus-5 hit the 120 s ceiling, was retried, hit it again, and the run
 * produced nothing in three minutes — not because anything was broken, but
 * because a plan for a real task does not fit in two minutes on a strong model.
 * Retrying a node that was making progress is worse than waiting: it pays for
 * the whole turn again and lands in the same wall.
 *
 * Scaling factors, not absolute numbers, so a user who raises or lowers
 * `defaultTimeoutMs` keeps the relative shape:
 *
 *  - planning and architecture think for a long time before emitting anything;
 *  - implementation runs tools in a loop, which is the longest phase of all;
 *  - reviewers read a bounded diff and answer, so they stay near the base.
 *
 * Pure by design: this file has no Android imports, so the numbers are testable
 * off-device. Every past timeout bug here was found by installing an APK.
 */
object AgentNodeTimeout {

    /**
     * Multiplier applied to the graph's base timeout for [role].
     *
     * Kept as a whole-number-ish scale rather than per-role millisecond
     * constants: the base is user-configurable, and absolute constants would
     * silently override that choice.
     */
    fun multiplierFor(role: AgentRole): Double = when (role) {
        // Thinks the longest and produces the plan every other node depends on.
        AgentRole.ORCHESTRATOR -> 3.0
        AgentRole.SOLUTION_ARCHITECT -> 3.0
        // Tool loops: read files, run commands, iterate.
        AgentRole.SENIOR_IMPLEMENTER -> 4.0
        AgentRole.CODEBASE_DISCOVERY -> 2.5
        AgentRole.INDEPENDENT_TEST_DESIGNER -> 2.5
        // Bounded reading tasks — a modest bump over a chat turn is enough.
        AgentRole.CODE_CORRECTNESS_REVIEWER,
        AgentRole.SECURITY_REVIEWER,
        AgentRole.PERFORMANCE_REVIEWER,
        AgentRole.TEST_QUALITY_AUDITOR,
        AgentRole.DEPENDENCY_GUARDIAN,
        AgentRole.REQUIREMENTS_ANALYST,
        AgentRole.DOCUMENTATION_AGENT,
        AgentRole.FINAL_GATEKEEPER -> 1.5
    }

    /** Absolute floor: below this even a trivial reply cannot land. */
    const val MIN_MS: Long = 60_000L

    /**
     * Absolute ceiling per node turn.
     *
     * A node that has produced nothing in twelve minutes is stuck, not slow, and
     * an unbounded wait on a phone means the user stares at a spinner while the
     * process risks being killed anyway. This is the "give up and say so" line,
     * not a performance target.
     */
    const val MAX_MS: Long = 720_000L

    /**
     * Effective timeout for [role] given the graph's [baseTimeoutMs].
     *
     * Clamped on both ends so a misconfigured graph (0, or a week) cannot make
     * the engine either unusable or unbounded.
     */
    fun timeoutMsFor(role: AgentRole, baseTimeoutMs: Long): Long {
        val scaled = (baseTimeoutMs.coerceAtLeast(0L) * multiplierFor(role)).toLong()
        return scaled.coerceIn(MIN_MS, MAX_MS)
    }

    /**
     * Whether a node that already spent [elapsedMs] deserves another attempt.
     *
     * A retry only helps when the first attempt failed early — a transport blip,
     * a refused connection. When the attempt burned the whole timeout, the model
     * was working and simply needed more room; retrying pays full price to hit
     * the identical wall, which is exactly what happened on run f0949263. Past
     * this fraction of the budget the run should escalate instead.
     */
    fun shouldRetryAfterTimeout(elapsedMs: Long, timeoutMs: Long): Boolean {
        if (timeoutMs <= 0L) return false
        return elapsedMs < timeoutMs * 0.75
    }
}
