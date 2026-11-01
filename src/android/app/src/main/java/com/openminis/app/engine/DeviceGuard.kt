package com.openminis.app.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * [B20 Thermal & RAM Guard] The mechanical hardware watchdog the user asked
 * for: when the device overheats or runs out of memory, the engine's
 * parallelism shrinks AUTOMATICALLY (divider 1 -> 2 -> 3 -> 6), so long
 * agent runs never push the phone into "sandbox not responding". The guard
 * only clamps ceilings — it never cancels running work, so mini-agents
 * already in flight finish their task.
 *
 * Pure decision core ([dividerFor]) is unit-pinned; the Android listeners
 * (PowerManager.OnThermalStatusListener API 29+, ActivityManager.MemoryInfo
 * poll) are thin adapters started from MinisApp.
 */
object DeviceGuard {

    private const val TAG = "DeviceGuard"

    /** PowerManager.THERMAL_STATUS_* -> parallelism divider. */
    fun dividerFor(thermalStatus: Int, lowRam: Boolean): Int {
        val thermal = when {
            thermalStatus >= PowerManager.THERMAL_STATUS_EMERGENCY -> 6
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> 3
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> 2
            else -> 1
        }
        val ram = if (lowRam) 2 else 1
        return (thermal * ram).coerceIn(1, 6)
    }

    /**
     * Wire the listeners. Never throws — a device without the thermal
     * service just keeps divider 1.
     */
    fun start(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null) {
                pm.addThermalStatusListener { status ->
                    apply(status, isLowRam(context))
                }
            }
            apply(currentThermal(context), isLowRam(context))
        } catch (e: Exception) {
            Log.w(TAG, "DeviceGuard start failed: ${e.message}")
        }
    }

    private fun currentThermal(context: Context): Int = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null) {
            pm.currentThermalStatus
        } else 0
    } catch (e: Exception) {
        0
    }

    private fun isLowRam(context: Context): Boolean = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        mi.lowMemory
    } catch (e: Exception) {
        false
    }

    private fun apply(thermalStatus: Int, lowRam: Boolean) {
        val divider = dividerFor(thermalStatus, lowRam)
        if (divider != AgentConcurrency.thermalDivider) {
            AgentConcurrency.thermalDivider = divider
            Log.w(
                TAG,
                "thermal=$thermalStatus lowRam=$lowRam -> parallelism divider $divider " +
                    "(ceiling now ${AgentConcurrency.parallel})",
            )
        }
    }
}
