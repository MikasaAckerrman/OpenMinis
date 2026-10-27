package com.openminis.app.engine

/**
 * [T-parallel-agents] The one place that decides how much work may run at
 * the same time.
 *
 * Two gates used to disagree. The scheduler let a wave hold four calls
 * (`maxConcurrent = 4`) while the executor's semaphore let only three
 * subagents through, so the fourth spawn of a wave silently waited behind a
 * gate the scheduler knew nothing about. One source of truth removes that
 * class of surprise: both gates read the same number.
 *
 * The number follows the device, not a guess. More agents is not better
 * here: every parallel subagent is a live model stream, a UI row, a socket
 * and a share of the provider's rate limit. A weak phone keeps the old
 * ceiling of three, a current flagship gets six. The rule is a pure
 * function, so a test can pin it.
 */
object AgentConcurrency {

    /** How many subagents may run in the foreground at the same time. */
    fun parallelCeiling(cores: Int): Int = when {
        cores >= 8 -> 6
        cores >= 6 -> 5
        cores >= 4 -> 4
        else -> 3
    }

    /**
     * Background subagents live longer and keep their session, so their
     * ceiling stays one step below the foreground one.
     */
    fun backgroundCeiling(cores: Int): Int = (parallelCeiling(cores) - 1).coerceAtLeast(2)

    /** The cores of the device the app runs on. */
    fun deviceCores(): Int = Runtime.getRuntime().availableProcessors()

    /** Foreground ceiling for this device. */
    val parallel: Int = parallelCeiling(deviceCores())

    /** Background ceiling for this device. */
    val background: Int = backgroundCeiling(deviceCores())
}
