package com.openminis.app.service

import android.util.Log

/**
 * [B27-A] Guard around WindowManager attachment.
 *
 * `windowManager.addView` throws (`BadTokenException`,
 * `InvalidDisplayException`, `WindowLeaked`-family) when the window token
 * died behind the service's back — the classic shape is an OEM/system
 * reclaim or a display teardown while the process sat in the background,
 * then a tool tick attaching the session capsule. Without the guard that
 * throw is an UNCAUGHT crash inside a background service: the
 * "guaranteed crash in the background" report. With it the overlay stays
 * detached and the next attach() retries with a fresh token.
 *
 * The seam is a lambda so the failure path is testable on the JVM without
 * a WindowManager double (WindowManagerImpl is final; faking the whole
 * interface is brittle against SDK drift).
 */
object OverlayAttachGuard {

    fun addViewSafely(tag: String, add: () -> Unit): Boolean = try {
        add()
        true
    } catch (t: Throwable) {
        Log.w(
            tag,
            "windowManager.addView failed (${t.javaClass.simpleName}: ${t.message}) — " +
                "overlay stays detached, next attach retries",
        )
        false
    }
}
