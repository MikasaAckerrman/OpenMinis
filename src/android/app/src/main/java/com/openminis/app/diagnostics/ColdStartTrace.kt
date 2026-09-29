package com.openminis.app.diagnostics

import android.os.SystemClock
import android.util.Log
import com.openminis.app.logging.AppLogger

/**
 * [T-cold-start-trace] The one leg of the lag hunt that never had data:
 * cold start. jankStats covers frames and HangDetector covers stalls, but
 * process-spawn → first usable frame was only ever visible as a user
 * complaint («после обновления лаги»). Three stamps, elapsed-realtime
 * based (immune to wall-clock jumps):
 *
 *  1. [appCreateStart] — MinisApp.onCreate entry (as close to process
 *     start as Application code gets; ART/classload before us is the
 *     fixed cost we cannot reduce anyway).
 *  2. [appCreateDone] — end of the heavy subsystem bring-up (DB,
 *     repositories, offload server, bind mounts…).
 *  3. [firstFrame] — the first frame the main Choreographer completes
 *     after MainActivity sets its content.
 *
 * Each transition logs the DELTA, so a slow segment names itself:
 * appInit>250ms → move subsystems off the critical path;
 * activity→firstFrame>150ms → composition/redraw pressure.
 *
 * Logcat tag "Minis.ColdStart" + AppLogger "ColdStart" so the numbers
 * survive to the daily file AND are readable via debug.logs.read.
 */
object ColdStartTrace {

    private const val TAG = "Minis.ColdStart"

    @Volatile private var appCreateStartMs: Long = 0L
    @Volatile private var appCreateDoneMs: Long = 0L
    @Volatile private var activityCreatedMs: Long = 0L
    @Volatile private var firstFrameLogged = false

    fun appCreateStart() {
        if (appCreateStartMs != 0L) return
        appCreateStartMs = SystemClock.elapsedRealtime()
    }

    fun appCreateDone() {
        if (appCreateStartMs == 0L) return
        appCreateDoneMs = SystemClock.elapsedRealtime()
        val ms = appCreateDoneMs - appCreateStartMs
        Log.i(TAG, "coldStart appInit ${ms}ms")
        AppLogger.info("ColdStart", "appInit done in ${ms}ms (process→subsystems ready)")
    }

    /** Call from MainActivity right after setContent — stamps activity setup. */
    fun activityCreated() {
        if (appCreateStartMs == 0L) return
        activityCreatedMs = SystemClock.elapsedRealtime()
        val ms = activityCreatedMs - appCreateStartMs
        Log.i(TAG, "coldStart activityCreated ${ms}ms")
        AppLogger.info("ColdStart", "activityCreated at ${ms}ms from process start")
    }

    /**
     * Call after content is set; the NEXT completed frame is the first
     * drawn frame. Idempotent across recompositions/navigation.
     */
    fun awaitFirstFrame() {
        if (firstFrameLogged) return
        firstFrameLogged = true
        android.view.Choreographer.getInstance().postFrameCallback { _ ->
            val ms = SystemClock.elapsedRealtime() - appCreateStartMs
            val activityToFrame =
                if (activityCreatedMs > 0) SystemClock.elapsedRealtime() - activityCreatedMs else -1
            Log.i(TAG, "coldStart firstFrame ${ms}ms (activity→frame ${activityToFrame}ms)")
            AppLogger.info(
                "ColdStart",
                "firstFrame at ${ms}ms from process start (activity→frame ${activityToFrame}ms)",
            )
        }
    }
}
