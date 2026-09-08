package com.openminis.app.browser

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import androidx.core.content.ContextCompat
import com.openminis.app.logging.AppLogger

/**
 * [T-browser-camera-gate] Single gate for WebView camera (getUserMedia)
 * requests in BOTH browser surfaces — the user-facing preview browser
 * ([com.openminis.app.ui.preview.WebViewHolder]) and the agent automation
 * browser ([BrowserUseManager]).
 *
 * ## Why this exists
 *
 * WebChromeClient.onPermissionRequest was never overridden, so any page
 * calling getUserMedia() (camera) failed silently — no permission dialog,
 * no error the user could act on. That blocked identity/liveness checks
 * ("verify your face") inside the built-in browser, forcing the user to
 * jump to an external browser mid-flow and losing the session cookies.
 *
 * ## Security contract (deliberate choices)
 *
 *  - OFF by default. The camera is a privacy-sensitive sensor; it stays
 *    dark until the user flips the toggle in Settings → Permissions.
 *  - VIDEO ONLY. Audio capture is never granted — the requesting page gets
 *    a subset grant when it asked for video+audio, and a plain deny when
 *    it asked for audio alone. Liveness checks need video; a microphone
 *    feed has no business being handed to a webview.
 *  - Both gates must hold: the in-app toggle AND the OS runtime CAMERA
 *    permission. Either missing → deny. The OS permission is requested at
 *    toggle-on time (Settings screen), never from the webview callback —
 *    a page cannot cause a permission dialog to appear.
 *  - No per-origin memory, no "remember for this site": the toggle is
 *    global, matching the app's existing simple permission model.
 *
 * SharedPreferences (not a repository) so both WebView surfaces can read
 * the flag synchronously on the UI thread without holding a reference to
 * app-scoped DI — the WebChromeClient callback has no clean injection
 * path and must not block.
 */
object BrowserCameraGate {

    private const val TAG = "BrowserCameraGate"
    private const val PREFS_NAME = "browser_camera_gate"

    /** Pref key for the user toggle. Default false — camera stays off. */
    const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The in-app toggle state. Defaults to OFF. */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    /** Persist the toggle. Called from the Settings screen only. */
    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
        AppLogger.info(TAG, "browser camera toggle -> $value")
    }

    /** OS-level CAMERA runtime permission state. */
    fun hasOsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context.applicationContext,
            android.Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Decide a WebView [PermissionRequest]. Must be called on the UI thread
     * (WebChromeClient callbacks are). Grants ONLY
     * [PermissionRequest.RESOURCE_VIDEO_CAPTURE]; every other resource —
     * audio capture, protected media id, MIDI — is denied.
     *
     * Subset grants: a page asking for {video, audio} receives {video} —
     * the API accepts a subset and pages fall back to video-only streams,
     * which is exactly what a liveness check wants. Audio-only requests
     * get a full deny.
     *
     * @return true if video was granted, false if denied.
     */
    fun handle(request: PermissionRequest, context: Context): Boolean {
        val wantsVideo = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        if (!wantsVideo) {
            AppLogger.info(TAG, "deny: no video resource in ${request.resources.toList()}")
            request.deny()
            return false
        }
        if (!isEnabled(context)) {
            AppLogger.info(TAG, "deny: toggle is off (enable in Settings → Permissions)")
            request.deny()
            return false
        }
        if (!hasOsPermission(context)) {
            AppLogger.warning(TAG, "deny: OS CAMERA permission not granted")
            request.deny()
            return false
        }
        AppLogger.info(TAG, "grant: VIDEO_CAPTURE (video only, audio stripped)")
        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        return true
    }
}
