package com.openminis.app.diagnostics

import android.os.Debug
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Allocation probe — answers one question on a REAL device in one call:
 * is the GC pressure churn (short-lived garbage at a high rate) or a real
 * leak (retained set growing)?
 *
 * The 19.10 incident log showed "GC freed 70-140MB" every ~1.5s with the
 * post-GC heap flat at 169-205MB. Flat post-GC heap means CHURN, but only a
 * live measurement can separate "high garbage rate with bounded live set"
 * from "bounded leak at a high allocation rate".
 *
 * [android.os.Debug.getAllocatedBytes] is a process-lifetime counter of bytes
 * the runtime has allocated (API 23+). Differencing it across a window gives
 * the true allocation RATE in MB/s, which no logcat GC line can give you.
 *
 * Deliberately zero-cost when unused: no timer, no sampling thread, no hooks
 * in the hot path. It is pull-based, called from the debug RPC only.
 */
object AllocProbe {

    private val lastCounter = AtomicLong(-1L)
    private val lastAt = AtomicLong(0L)

    /** Instantaneous counters, no sleeping. */
    fun snapshot(): JSONObject {
        val rt = Runtime.getRuntime()
        val javaUsed = rt.totalMemory() - rt.freeMemory()
        val allocTotal = Debug.getAllocatedBytes()
        val nativeAlloc = Debug.getNativeHeapAllocatedSize()
        val o = JSONObject()
            .put("javaHeapUsedMB", javaUsed / 1048576.0)
            .put("javaHeapTotalMB", rt.totalMemory() / 1048576.0)
            .put("javaHeapMaxMB", rt.maxMemory() / 1048576.0)
            .put("javaHeapFreePct", 100.0 * rt.freeMemory() / rt.totalMemory())
            .put("nativeHeapMB", nativeAlloc / 1048576.0)
            .put("allocatedTotalMB", allocTotal / 1048576.0)
            .put("uptimeMs", SystemClock.elapsedRealtime())
        return o
    }

    /**
     * Measure the allocation rate over [intervalMs].
     *
     * Returns the same counters plus:
     *   allocRateMBps  - bytes allocated per second (THE number to look at)
     *   allocDeltaMB  - bytes allocated during the window
     *   liveSetMB     - java heap in use (grows under a real leak)
     *
     * A leak shows liveSetMB climbing across repeated calls; churn shows a
     * large allocRateMBps with a flat liveSetMB. Call it repeatedly during the
     * same long session to see the trend.
     */
    fun measure(intervalMs: Long): JSONObject {
        val window = intervalMs.coerceIn(200L, 60_000L)
        val a0 = Debug.getAllocatedBytes()
        val rt0 = Runtime.getRuntime()
        val live0 = rt0.totalMemory() - rt0.freeMemory()
        val t0 = SystemClock.elapsedRealtime()

        Thread.sleep(window)

        val a1 = Debug.getAllocatedBytes()
        val rt1 = Runtime.getRuntime()
        val live1 = rt1.totalMemory() - rt1.freeMemory()
        val dt = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)

        val delta = a1 - a0
        lastCounter.set(a1)
        lastAt.set(SystemClock.elapsedRealtime())

        return snapshot()
            .put("intervalMs", dt)
            .put("allocDeltaMB", delta / 1048576.0)
            .put("allocRateMBps", delta / 1048576.0 / (dt / 1000.0))
            .put("liveSetStartMB", live0 / 1048576.0)
            .put("liveSetEndMB", live1 / 1048576.0)
            .put("liveSetDeltaMB", (live1 - live0) / 1048576.0)
    }

    /** Rate between two explicit snapshots, without sleeping. */
    fun rate(): JSONObject {
        val prevAlloc = lastCounter.get()
        val prevAt = lastAt.get()
        val now = snapshot()
        if (prevAlloc < 0 || prevAt <= 0L) {
            return now.put("note", "call debug.alloc.measure first to prime the delta")
        }
        val dt = (SystemClock.elapsedRealtime() - prevAt).coerceAtLeast(1L)
        val delta = Debug.getAllocatedBytes() - prevAlloc
        lastCounter.set(Debug.getAllocatedBytes())
        lastAt.set(SystemClock.elapsedRealtime())
        return now
            .put("sinceMs", dt)
            .put("allocDeltaMB", delta / 1048576.0)
            .put("allocRateMBps", delta / 1048576.0 / (dt / 1000.0))
    }
}