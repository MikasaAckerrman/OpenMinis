package com.openminis.app.diagnostics

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import com.openminis.app.logging.AppLogger
import java.util.ArrayDeque

/**
 * Frame-level jank monitor — the missing half of [HangDetector].
 *
 * HangDetector catches the 3s+ full freezes. The user-reported complaint is
 * different: "лагает" — sub-second stutters that never trip a 3s hang and
 * leave no trace anywhere. This object is that trace.
 *
 * HOW IT WORKS. A Choreographer frame callback measures the vsync-to-vsync
 * delta of EVERY frame. When a frame takes more than [MIN_SKIPPED_FRAMES]
 * vsyncs (~67ms @60Hz, ~33ms @120Hz — a visible stutter), the monitor logs:
 *   1. the actual frame duration,
 *   2. the recent UI-operation markers (see [mark]) with their ages,
 *   3. "no markers" when nothing instrumented ran — which points at
 *      GC / binder / native work, not our Kotlin UI code.
 *
 * THE MARKERS are the whole point. "frame took 240ms" is a symptom; "frame
 * took 240ms after `overlay.panel open rows=8` +12ms and `msgs emit n=6120`
 * +40ms" is a diagnosis. Instrumentation points call [mark] at the START of
 * a UI operation; the ring keeps the last [MARKER_CAPACITY] entries.
 *
 * COST. One postFrameCallback repost + one long comparison per frame (µs),
 * a ring push per marker, one rate-limited log line per jank episode. The
 * 30s summary line only fires when at least one jank was seen.
 *
 * OUTPUT. Logcat tag "Minis.Jank" (greppable: `logcat -s Minis.Jank`) and
 * the daily AppLogger file, so a lag report can be pulled from either.
 */
object JankMonitor {

    private const val TAG = "Minis.Jank"
    private const val FILE_TAG = "JankMonitor"

    /** Frames slower than this many vsyncs count as jank. 4 ≈ 67ms @60Hz. */
    private const val MIN_SKIPPED_FRAMES = 4

    /** At most one attribution report per this window (anti-spam). */
    private const val RATE_LIMIT_MS = 1_200L

    private const val MARKER_CAPACITY = 24

    /** How many recent markers are quoted in one attribution report. */
    private const val MARKERS_IN_REPORT = 8

    /** Counters window for the periodic summary line. */
    private const val SUMMARY_INTERVAL_MS = 30_000L

    class Marker(val atMs: Long, val label: String)

    private val markers = ArrayDeque<Marker>()

    private var framePeriodNanos = 1_000_000_000L / 60L
    private var lastFrameNanos = 0L
    private var lastReportMs = 0L
    private var lastSummaryMs = 0L

    private var jankCount = 0
    private var framesInWindow = 0L
    private var worstFrameMs = 0L
    private var started = false

    /** True while the frame loop is posted. Toggled from activity lifecycle. */
    private var active = false

    fun start(context: Context) {
        if (started) return
        started = true
        try {
            val display = if (android.os.Build.VERSION.SDK_INT >= 30) {
                context.display
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay
            }
            val hz = display?.refreshRate ?: 60f
            if (hz > 1f) framePeriodNanos = (1_000_000_000.0 / hz).toLong()
        } catch (_: Exception) {
            // stay at 60Hz assumption
        }
        Log.i(TAG, "start: refresh=${"%.1f".format(1_000_000_000.0 / framePeriodNanos)}Hz " +
            "threshold=${MIN_SKIPPED_FRAMES} vsyncs (~${MIN_SKIPPED_FRAMES * framePeriodNanos / 1_000_000}ms)")
        setActive(true)
    }

    /**
     * Gate the frame loop on app visibility.
     *
     * An always-reposted Choreographer callback keeps the vsync subscription
     * alive 24/7 — the main thread wakes 60-120 times a second forever, even
     * with a static screen and no animation running. MinisApp's lifecycle
     * callbacks call this with the foreground activity count. When the app is
     * backgrounded the loop stops; the session overlay still self-times its
     * own onDraw in that state, so background overlay cost stays observable.
     *
     * MUST be called on the main thread (lifecycle callbacks guarantee it).
     */
    fun setActive(active: Boolean) {
        if (!started || this.active == active) return
        this.active = active
        if (active) {
            // A stale lastFrameNanos would make the FIRST frame after a pause
            // look like a multi-second jank — reset the baseline.
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
        Log.i(TAG, "setActive($active)")
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!active) return // deactivated between schedule and fire — do not repost
            framesInWindow++
            if (lastFrameNanos != 0L) {
                val deltaNanos = frameTimeNanos - lastFrameNanos
                if (deltaNanos > framePeriodNanos * MIN_SKIPPED_FRAMES) {
                    onJank(deltaNanos / 1_000_000L)
                }
            }
            lastFrameNanos = frameTimeNanos
            maybeSummary()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun onJank(frameMs: Long) {
        jankCount++
        if (frameMs > worstFrameMs) worstFrameMs = frameMs
        val now = SystemClock.elapsedRealtime()
        if (now - lastReportMs < RATE_LIMIT_MS) return
        lastReportMs = now

        val recent = synchronized(markers) {
            val tail = ArrayList<Marker>(MARKERS_IN_REPORT)
            val it = markers.descendingIterator()
            while (it.hasNext() && tail.size < MARKERS_IN_REPORT) tail.add(it.next())
            tail
        }
        val attributed = if (recent.isEmpty()) {
            "no markers in window — suspect GC / binder / native, not instrumented UI code"
        } else {
            recent.joinToString(" | ") { m -> "${m.label}(-${now - m.atMs}ms)" }
        }
        val vsyncs = (frameMs * 1_000_000L / framePeriodNanos).toInt()
        val line = "frame ${frameMs}ms (~$vsyncs vsyncs): $attributed"
        Log.w(TAG, line)
        AppLogger.warning(FILE_TAG, line)
    }

    private fun maybeSummary() {
        if (jankCount == 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSummaryMs < SUMMARY_INTERVAL_MS) return
        lastSummaryMs = now
        val line = "summary ${SUMMARY_INTERVAL_MS / 1000}s: janks=$jankCount worst=${worstFrameMs}ms " +
            "frames=$framesInWindow"
        Log.w(TAG, line)
        AppLogger.warning(FILE_TAG, line)
        jankCount = 0
        worstFrameMs = 0L
        framesInWindow = 0L
    }

    /**
     * Record that a UI operation is starting. Cheap: one timestamp + a ring
     * push. Call from any thread; the label should carry sizes/counts, not
     * whole objects ("msgs emit n=6120", "overlay panel rows=8").
     */
    fun mark(label: String) {
        if (!started) return
        synchronized(markers) {
            markers.addLast(Marker(SystemClock.elapsedRealtime(), label))
            while (markers.size > MARKER_CAPACITY) markers.removeFirst()
        }
    }
}
