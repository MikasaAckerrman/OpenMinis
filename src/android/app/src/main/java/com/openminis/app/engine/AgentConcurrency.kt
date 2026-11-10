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

    /**
     * [B20 Thermal & RAM Guard] Mechanical clamp: DeviceGuard raises this
     * divider when the device overheats or runs out of memory, and the
     * ceilings shrink accordingly (6 -> 3 -> 2 -> 1). A getter, not a val:
     * consumers re-read it on every turn, so the clamp applies without an
     * app restart.
     */
    @Volatile
    var thermalDivider: Int = 1

    /**
     * [B48-thermal-steps] The raw PowerManager thermal status, published
     * by [DeviceGuard.apply] — the vc162 incident showed divider division
     * alone left THREE workers running at THERMAL_STATUS_MODERATE (6/2=3):
     * the ptrace storm saturated every core and the device throttled
     * harder. The ceilings now step on the STATUS itself (literals keep
     * this file JVM-pinnable): SEVERE(4)+ -> one foreground worker,
     * MODERATE(2)+ -> two, background work single-filed at MODERATE+.
     */
    @Volatile
    var thermalStatus: Int = 0

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

    /** Foreground ceiling for this device, thermal-stepped. */
    val parallel: Int
        get() = when {
            thermalStatus >= 4 -> 1 // PowerManager.THERMAL_STATUS_SEVERE and up
            thermalStatus >= 2 -> 2 // PowerManager.THERMAL_STATUS_MODERATE and up
            else -> (parallelCeiling(deviceCores()) / thermalDivider).coerceAtLeast(1)
        }

    /** Background ceiling for this device, thermal-stepped: no parallel
     *  background work at all while the chip is above moderate. */
    val background: Int
        get() = when {
            thermalStatus >= 2 -> 1
            else -> (backgroundCeiling(deviceCores()) / thermalDivider).coerceAtLeast(1)
        }

    /**
     * [P1-io-concurrency] Ceiling for DISK/NETWORK tools (file_read,
     * file_write, file_edit, read_image, webfetch — see
     * [ToolConcurrencyClass]). A file write holds no model stream, no UI
     * row, no share of the provider's rate limit — the CPU-derived
     * parallelCeiling was throttling them pointlessly: 10 parallel writes
     * to 10 distinct files ran 6-and-wait. The I/O number follows flash
     * queue depth, not cores, and deliberately does NOT take the thermal
     * divider: thermal pressure targets CPU work (streams, spawns); a
     * handful of concurrent small writes is not a thermal factor.
     * Consumer: EngineAgentLoop's second semaphore — ToolConcurrencyClass
     * routes each call to its gate.
     */
    fun ioParallelCeiling(cores: Int): Int = if (cores >= 8) 16 else 8

    /** I/O ceiling for this device (NOT thermal-clamped — see above). */
    val ioParallel: Int get() = ioParallelCeiling(deviceCores())
}
