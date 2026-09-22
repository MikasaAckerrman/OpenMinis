package com.openminis.app.data

/**
 * Hard product boundary for context rewriting.
 *
 * Compact markers, rescue digests, and tool-output offloads change the payload
 * sent on subsequent turns. They may run only after an explicit user/operator
 * command. Context pressure, model changes, and error recovery may suggest a
 * command, but must never rewrite the session themselves.
 */
object CompactionLaunchPolicy {
    enum class Origin {
        EXPLICIT_USER,
        PRESSURE_MAINTENANCE,
        OVERSIZE_RECOVERY,
        MODEL_SWITCH,
        AUTOMATIC_OFFLOAD,
        /** [T-auto-mode] An armed autonomous run (armed by the user's own message). */
        AUTO_MODE_ARMED,
    }

    /**
     * EXPLICIT_USER remains the only rewrite path for interactive sessions.
     * AUTO_MODE_ARMED is the single deliberate exception (user decision,
     * 22.09.2026): an autonomous loop that parks its continuation and folds
     * history at 100% — the user explicitly consented by arming the run, and
     * nobody is watching to type /compact manually.
     */
    fun mayRewrite(origin: Origin): Boolean =
        origin == Origin.EXPLICIT_USER || origin == Origin.AUTO_MODE_ARMED
}
