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
    const val DEFAULT_TIMEOUT_MS = 90_000L

    /** The override (a settings seam may expose this) is clamped to this range. */
    const val MIN_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 180_000L

    /** Total event silence after which a member is pronounced wedged, ms. */
    const val STALL_TIMEOUT_MS = 30_000L

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
     * Stall verdict: KNOWN activity aged past the threshold. An unknown age
     * (null — the child has not begun its graph yet) is deliberately alive:
     * the hard ceiling owns that window, the stall detector owns silence
     * AFTER the run announced itself.
     */
    fun shouldStallCancel(idleMs: Long?, stallTimeoutMs: Long = STALL_TIMEOUT_MS): Boolean =
        idleMs != null && idleMs >= stallTimeoutMs

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
