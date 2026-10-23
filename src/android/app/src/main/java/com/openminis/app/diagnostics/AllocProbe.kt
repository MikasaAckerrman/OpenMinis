package com.openminis.app.diagnostics

import android.os.Debug
import android.os.SystemClock
import org.json.JSONObject

/**
 * Allocation probe — answers one question on a REAL device in one call:
 * is the GC pressure churn (short-lived garbage at a high rate) or a real
 * leak (retained set growing)?
 *
 * The 19.10 incident log showed "GC freed 70-140MB" every ~1.5s with the
 * post-GC heap flat at 169-205MB. A flat post-GC heap points at churn, but
 * only a live measurement can separate "high garbage rate with a bounded
 * live set" from "bounded leak at a high allocation rate" — and logcat GC
 * lines cannot, because they only report what a GC already reclaimed.
 *
 * HOW IT MEASURES, without any uncertain platform API:
 *   java heap in use = totalMemory() - freeMemory()   (Runtime, always there)
 * As the runtime allocates, this value climbs until a GC drops it back —
 * the classic sawtooth that the "AllocSpace" lines in the logcat GC output
 * are reporting from the other side. Summing every POSITIVE step over a
 * window therefore gives bytes allocated in that window, i.e. exactly the
 * allocation rate, and is API-independent.
 *
 * Read the two numbers together:
 *   allocRateMBps high + liveSet flat  -> churn (fix the hot path)
 *   allocRateMBps high + liveSet rising -> leak (fix the retention)
 *
 * Deliberately zero-cost when unused: no timer, no sampling thread, no
 * hooks anywhere in the streaming path. Pull-based, from the debug RPC only.
 */
object AllocProbe {

    private const val SAMPLE_MS = 100L

    private fun javaUsedBytes(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    /** Instantaneous counters, no sleeping. */
    fun snapshot(): JSONObject {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        return JSONObject()
            .put("javaHeapUsedMB", used / 1048576.0)
            .put("javaHeapTotalMB", rt.totalMemory() / 1048576.0)
            .put("javaHeapMaxMB", rt.maxMemory() / 1048576.0)
            .put("javaHeapFreePct", 100.0 * rt.freeMemory() / rt.totalMemory())
            .put("nativeHeapMB", Debug.getNativeHeapAllocatedSize() / 1048576.0)
            .put("uptimeMs", SystemClock.elapsedRealtime())
    }

    /**
     * Measure the allocation rate over [intervalMs].
     *
     * Adds to [snapshot]:
     *   allocDeltaMB   - bytes allocated during the window
     *   allocRateMBps  - allocDeltaMB per second — THE number to look at
     *   liveSetMinMB   - lowest heap-in-use seen (the post-GC floor)
     *   liveSetMaxMB   - highest heap-in-use seen (the pre-GC peak)
     *   gcCount        - GC cycles observed in the window
     *   reclaimedMB    - bytes the GC cycles gave back
     *
     * Counting rule: ONLY positive steps count as allocation. A GC does not
     * create allocations, it reclaims memory that was allocated earlier, so
     * adding the drop to the allocation total double-counted every byte:
     * the same bytes were counted once when allocated and again when
     * reclaimed, which inflated the reported rate roughly twofold.
     *
     * The measure under-counts in one direction: if the app allocates and
     * frees inside a single SAMPLE_MS window, the net step can be smaller
     * than the true traffic. That makes the figure a LOWER bound, which is
     * the safe direction — it never claims more churn than happened.
     *
     * Call it repeatedly during one long session: a rising liveSetMinMB across
     * calls is a leak; a flat liveSetMinMB with a large allocRateMBps is churn.
     */
    fun measure(intervalMs: Long): JSONObject {
        val window = intervalMs.coerceIn(300L, 120_000L)
        val deadline = SystemClock.elapsedRealtime() + window

        var prev = javaUsedBytes()
        var min = prev
        var max = prev
        var allocated = 0L
        var reclaimed = 0L
        var gcCount = 0

        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(SAMPLE_MS)
            val now = javaUsedBytes()
            val delta = now - prev
            if (delta > 0) {
                allocated += delta
            } else if (delta < 0) {
                // Heap dropped: a GC reclaimed memory allocated earlier.
                // This is NOT new allocation — it is reported separately.
                reclaimed += -delta
                gcCount++
            }
            if (now < min) min = now
            if (now > max) max = now
            prev = now
        }

        val elapsed = window.coerceAtLeast(1L)
        return snapshot()
            .put("intervalMs", elapsed)
            .put("allocDeltaMB", allocated / 1048576.0)
            .put("allocRateMBps", allocated / 1048576.0 / (elapsed / 1000.0))
            .put("liveSetMinMB", min / 1048576.0)
            .put("liveSetMaxMB", max / 1048576.0)
            .put("gcCount", gcCount)
            .put("reclaimedMB", reclaimed / 1048576.0)
    }
}