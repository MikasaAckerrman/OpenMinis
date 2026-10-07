package com.openminis.app.diagnostics

import android.util.Log
import com.openminis.app.logging.AppLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [T-thread-watch] OS-thread exhaustion guard.
 *
 * TWO background deaths on 07.10 (both SIGABRT, thread ids 32560 and 23261)
 * were `OutOfMemoryError: pthread_create failed` — the process had created
 * 32k+ OS threads over hours of background life. The frame-based JankMonitor
 * is silent in the background (no frames = no summaries), so the churn was
 * invisible until it killed the process.
 *
 * This watch owns a single daemon thread that ticks every 30s for as long as
 * the process lives — foreground or not:
 *  - SOFT (>400): a one-line trend notice, rate-limited to one per 5 min;
 *  - DUMP (>800): a histogram of live thread names — the churn SOURCE names
 *    itself (DefaultDispatch / NDK MediaCodec / pool-N / PersistentShell…),
 *    rate-limited to one per minute;
 *  - ALARM (>2000): critical line — long before pthread_create exhausts.
 *
 * The tick is one Thread.activeCount() call; the histogram (the only cost)
 * runs only in an anomalous state and is capped. Overhead in the healthy
 * case: ~zero.
 */
object ThreadWatch {
    private const val TAG = "ThreadWatch"
    private const val FILE_TAG = "[ThreadWatch]"
    private const val TICK_MS = 30_000L
    private const val SOFT = 400
    private const val DUMP = 800
    private const val ALARM = 2_000

    private val running = AtomicBoolean(false)
    private var lastSoftMs = 0L
    private var lastDumpMs = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({
            while (true) {
                try {
                    Thread.sleep(TICK_MS)
                    tick()
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (t: Throwable) {
                    // The watch must never become the crash source.
                    runCatching { Log.w(TAG, "tick failed: ${t.message}") }
                }
            }
        }, "thread-watch").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        Log.i(TAG, "started: thresholds soft=$SOFT dump=$DUMP alarm=$ALARM")
    }

    private fun tick() {
        val count = Thread.activeCount()
        val now = System.currentTimeMillis()
        when {
            count >= ALARM -> log("CRITICAL threads=$count — pthread exhaustion approaching", force = true)
            count >= DUMP -> {
                if (now - lastDumpMs >= 60_000L) {
                    lastDumpMs = now
                    log("threads=$count — top groups: ${histogram()}", force = true)
                }
            }
            count >= SOFT -> {
                if (now - lastSoftMs >= 300_000L) {
                    lastSoftMs = now
                    log("soft threads=$count (growing?)", force = false)
                }
            }
        }
    }

    /**
     * Live thread-name histogram, top 12 groups. Capped at 4000 sampled
     * threads — bounded cost even in a runaway state. Thread.enumerate
     * copies references only; no stacks are taken.
     */
    private fun histogram(): String {
        return try {
            val cap = 4000
            val probe = arrayOfNulls<Thread>(cap)
            val n = Thread.enumerate(probe)
            val groups = HashMap<String, Int>()
            for (i in 0 until n) {
                val name = probe[i]?.name ?: "null"
                // Pool/worker names carry instance digits (pool-3-thread-17);
                // group by the base so the histogram stays readable.
                val base = name.replace(Regex("-?\\d+$"), "")
                groups[base] = (groups[base] ?: 0) + 1
            }
            groups.entries.sortedByDescending { it.value }.take(12)
                .joinToString(", ") { "${it.key}=${it.value}" }
        } catch (t: Throwable) {
            "histogram failed: ${t.message}"
        }
    }

    private fun log(line: String, force: Boolean) {
        Log.w(TAG, line)
        // The file line is the half that survives the crash — the next
        // death of this class arrives WITH the source name attached.
        runCatching { AppLogger.warning(FILE_TAG, line) }
        if (force) Unit // reserved: escalation hook (notification, breaker)
    }
}
