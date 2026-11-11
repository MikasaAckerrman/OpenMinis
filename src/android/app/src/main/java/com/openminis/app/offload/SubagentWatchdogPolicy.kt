package com.openminis.app.offload

/**
 * [B36] Pure policy of the spawn_many watchdog: the hard per-agent ceiling,
 * the stall threshold it narrows that ceiling to, and the section texts a
 * degraded agent leaves in the batch result. Extracted from
 * [SubagentExecutor] because the executor itself has no test seam (it drives
 * real graph runs against real repositories) — this object carries every
 * DECISION, so the decision layer is unit-testable and the executor only
 * wires coroutines around it.
 *
 * The numbers follow the spec (docs/SUBAGENT_ZCODE_STYLE_SPEC.md Опора 3):
 * a batch member waits at most 90s by default (configurable 30-180s), and a
 * member that has filed NO event — no tool call, no node transition, no
 * stream heartbeat — for 30s is considered wedged and is cancelled, freeing
 * its slot for the siblings.
 */
object SubagentWatchdogPolicy {

    /** Default hard ceiling for one batch member, ms. */
    const val DEFAULT_TIMEOUT_MS = 90_000L // [B36] Verified by Senior Reviewer

    /** The override (a settings seam may expose this) is clamped to this range. */
    const val MIN_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 180_000L

    /**
     * Total event silence after which a member is pronounced wedged, ms.
     * [B45-stall-pulse] 30s → 60s: the vc158 live report had healthy
     * batch members killed at 30.001s because TTFT (first tokens from the
     * provider) took 32-45s under 3-way parallel load. The stall verdict
     * now also requires the stream to have proven life at least once
     * (see [shouldStallCancel]) — 60s of silence AFTER that is a real
     * wedge, 60s of first-token wait is a slow provider.
     */
    const val STALL_TIMEOUT_MS = 60_000L

    /** How often the watchdog polls the activity registry, ms. */
    const val STALL_CHECK_INTERVAL_MS = 5_000L

    /**
     * The effective per-agent ceiling: the override when present (clamped —
     * a bad settings write must not disable the watchdog either way), the
     * default otherwise.
     */
    fun effectiveTimeoutMs(overrideMs: Long?): Long =
        (overrideMs ?: DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    /**
     * [B49-spawn-timeout] The spawn_many `timeout` schema argument (seconds,
     * model-facing) as an override in ms. Absent or nonsense (0, negative)
     * returns null — the caller keeps [DEFAULT_TIMEOUT_MS]; the model must
     * never be able to SHRINK the ceiling to the 30s floor by sending 0.
     * Everything clamps through [effectiveTimeoutMs], so a request for 10s
     * or 500s lands in the same 30-180s envelope a settings override would.
     */
    fun fromToolArgSeconds(seconds: Int): Long? =
        if (seconds >= 1) effectiveTimeoutMs(seconds * 1000L) else null

    /**
     * Stall verdict: KNOWN activity aged past the threshold. An unknown age
     * (null — the child has not begun its graph yet) is deliberately alive:
     * the hard ceiling owns that window, the stall detector owns silence
     * AFTER the run announced itself.
     *
     * [B45-stall-pulse] TTFT rule: [hasStreamLife] gates the verdict — a
     * run whose stream has NEVER pulsed is the hard ceiling's problem
     * (waiting for the first token is not a wedged tool), the stall
     * detector owns silence after life was proven. Default true keeps the
     * pure-idle semantics for existing callers/tests.
     */
    fun shouldStallCancel(
        idleMs: Long?,
        stallTimeoutMs: Long = STALL_TIMEOUT_MS,
        hasStreamLife: Boolean = true,
    ): Boolean = hasStreamLife && idleMs != null && idleMs >= stallTimeoutMs

    /** Section text a timed-out member leaves in the batch result. */
    fun timeoutSection(timeoutMs: Long): String =
        "Timeout: subagent exceeded ${timeoutMs / 1000}s without finishing — " +
            "cancelled, its slot is free. Re-spawn with a narrower task if this work is still needed."

    /** Section text a stall-cancelled member leaves in the batch result. */
    fun stallSection(stallTimeoutMs: Long = STALL_TIMEOUT_MS): String =
        "Stalled: no tool, stream or node event for ${stallTimeoutMs / 1000}s — " +
            "cancelled by the watchdog. Re-spawn with a narrower task if this work is still needed."

    /**
     * Internal marker a stall-cancelled member carries through the
     * runCatching boundary so the executor can tell it apart from a plain
     * cancellation (user abort) and from the timeout's own cancellation.
     * Never escapes into a tool result — the executor swaps it for
     * [stallSection].
     */
    const val STALL_SENTINEL = "__stall_sentinel_b36__"
}
