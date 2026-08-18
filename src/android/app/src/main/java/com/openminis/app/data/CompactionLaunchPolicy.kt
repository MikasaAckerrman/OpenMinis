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
    }

    fun mayRewrite(origin: Origin): Boolean = origin == Origin.EXPLICIT_USER
}
