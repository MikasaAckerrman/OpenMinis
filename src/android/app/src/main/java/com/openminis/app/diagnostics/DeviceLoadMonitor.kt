package com.openminis.app.diagnostics

import android.content.Context
import android.os.BatteryManager
import com.openminis.app.service.SessionActivityTracker
import java.io.File

/**
 * [T-device-load-awareness] Per-request device-state visibility for the
 * agent. The user runs multiple concurrent agent sessions on one physical
 * phone: when the SoC heats up (cpu-* zones), heavy local work (kotlinc,
 * full-tree greps) makes everything worse — the assistant needs to SEE
 * that and choose lighter commands, which is exactly what happened
 * 2026-09-30 (zones at 86C while a second session streamed).
 *
 * Sources, all world-readable or framework APIs:
 *  - /sys/class/thermal (thermal_zoneNN/temp files, milli-degrees; cpu-*
 *    zones reflect the SoC, not the battery sensor the thermal-watch script uses)
 *  - BatteryManager capacity + charging state
 *  - SessionActivityTracker.activeSessions
 *
 * Snapshot is TTL-cached (30s): it is injected into every system prompt
 * build, and the zone scan (a directory listing + N tiny reads) is cheap
 * but not free on a hot device.
 */
object DeviceLoadMonitor {

    data class Snapshot(
        val topZoneC: Double,
        val batteryPct: Int?,
        val charging: Boolean?,
        val activeSessions: Int,
    ) {
        val hot: Boolean get() = topZoneC >= 70.0
        val warm: Boolean get() = topZoneC >= 60.0
    }

    private const val TTL_MS = 30_000L
    private var cache: Pair<Long, Snapshot>? = null

    fun snapshot(context: Context, force: Boolean = false): Snapshot {
        val now = android.os.SystemClock.elapsedRealtime()
        cache?.let { (at, snap) ->
            if (!force && now - at < TTL_MS) return snap
        }
        val snap = Snapshot(
            topZoneC = readTopZoneC(),
            batteryPct = readBatteryPct(context),
            charging = readCharging(context),
            activeSessions = runCatching {
                SessionActivityTracker.activeSessions.value.size
            }.getOrDefault(0),
        )
        cache = now to snap
        return snap
    }

    /** One prompt line — the agent's contract: read it, adapt behaviour. */
    fun describe(s: Snapshot): String {
        val thermal = when {
            s.hot -> "HOT (${"%.1f".format(s.topZoneC)}C) — NO heavy local work: no kotlinc, no full-tree scans; prefer CI for verification, keep turns short"
            s.warm -> "WARM (${"%.1f".format(s.topZoneC)}C) — avoid heavy local commands"
            else -> "ok (${"%.1f".format(s.topZoneC)}C)"
        }
        val batt = s.batteryPct?.let { pct ->
            val ch = if (s.charging == true) ", charging" else ""
            ", battery $pct%$ch"
        } ?: ""
        return "- Device thermal: $thermal$batt, agent sessions active: ${s.activeSessions}"
    }

    fun statsJson(): String {
        val (at, s) = cache ?: return "{\"cached\":false}"
        return buildString {
            append("{\"cached\":true,\"ageMs\":")
            append(android.os.SystemClock.elapsedRealtime() - at)
            append(",\"topZoneC\":").append(s.topZoneC)
            append(",\"hot\":").append(s.hot)
            append(",\"warm\":").append(s.warm)
            s.batteryPct?.let { append(",\"batteryPct\":").append(it) }
            s.charging?.let { append(",\"charging\":").append(it) }
            append(",\"activeSessions\":").append(s.activeSessions)
            append('}')
        }
    }

    private fun readTopZoneC(): Double {
        var top = 0.0
        val dir = File("/sys/class/thermal")
        val zones = dir.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return 0.0
        for (z in zones) {
            runCatching {
                val t = File(z, "temp").readText().trim().toDouble()
                if (t in 20_000.0..120_000.0 && t / 1000.0 > top) top = t / 1000.0
            }
        }
        return top
    }

    private fun readBatteryPct(context: Context): Int? = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    }.getOrNull()

    private fun readCharging(context: Context): Boolean? = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        when (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
    }.getOrNull()
}
