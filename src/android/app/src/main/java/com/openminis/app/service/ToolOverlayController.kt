package com.openminis.app.service

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.net.Uri
import android.view.animation.DecelerateInterpolator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.openminis.app.MinisApp
import com.openminis.app.R
import kotlin.math.abs

/**
 * T-bg-overlay phase 2: floating tool-status overlay.
 *
 * Adds a small draggable capsule via [WindowManager] using
 * TYPE_APPLICATION_OVERLAY (requires SYSTEM_ALERT_WINDOW). The capsule
 * shows the Minis launcher logo, the running tool's label, and a short
 * status — same data the FGS notification reads
 * (`SessionActivityTracker.currentToolName` / `currentToolStatus`).
 *
 * T-bg-overlay-polish: the per-tool icon was replaced with the Minis
 * launcher icon clipped to a circle, a thin stroke ring rotates around
 * the logo while a tool is running, and a small success/error glyph
 * sits next to the tool label once a tool finishes.
 *
 * Owned by [AgentForegroundService]; created on service start, destroyed
 * on service stop. Visibility is driven by the service's collector:
 *
 *   - Minis foreground OR no tool running OR user toggle OFF OR no perm → hidden
 *   - otherwise → shown, content updated on each tool-status change
 *
 * Position persists in [com.openminis.app.data.repository.BackgroundSettingsRepository]
 * across process restarts; first run defaults to the bottom-left corner
 * (10 dp from each edge). Tap dismisses to bring Minis back to the
 * foreground; drag moves the capsule.
 */
class ToolOverlayController(private val context: Context) {

    companion object {
        private const val TAG = "ToolOverlayController"
        private const val DRAG_SLOP_DP = 8f
        private const val EDGE_PADDING_DP = 10
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundRepo =
        (context.applicationContext as MinisApp).backgroundSettingsRepository

    private var view: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    // [T-android-overlay-reply-status-34599] Session ID associated with
    // the current overlay capsule. The whole-capsule tap builds a
    // `minis://session/<id>` deep-link to land back in the right chat;
    // null falls through to "just bring MainActivity forward" so we
    // never strand the user when no session id was published.
    private var pendingSessionId: String? = null
    /**
     * [T-android-overlay-reply-status-34599] Called when the user
     * dismisses the overlay (X button or tap-to-open). Lets the
     * service-level observer wipe the lingered completion state so the
     * AND-gate flips `shouldShow` to false on the next emission. Wired
     * by [AgentForegroundService] right after construction.
     */
    var onDismissByUser: (() -> Unit)? = null

    @Volatile
    var isShown: Boolean = false
        private set

    fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * Show or update the overlay. Safe to call repeatedly — content
     * updates if already shown, no-ops on permission denial.
     *
     * @param isRunning true while the tool is actively executing
     *        (drives the rotating ring + hides the success/error glyph).
     * @param outcome typed outcome of the most recently finished tool.
     *        Only consulted when [isRunning] = false:
     *          - Success → green ✓
     *          - Error / Timeout → red ✗
     *          - Cancelled / Unknown → glyph hidden (we don't have a
     *            confident signal to render).
     *        [T-overlay-glyph-typed-outcome] Replaces the previous
     *        [looksLikeFailure] text heuristic which always rendered
     *        green because it scanned the stale "Running: foo" status.
     */
    fun show(
        toolName: String?,
        statusText: String,
        isRunning: Boolean = true,
        outcome: ToolOutcome = ToolOutcome.Unknown,
        replyExcerpt: String? = null,
        targetSessionId: String? = null,
        toolTitle: String? = null,
    ) {
        Log.d(
            TAG,
            "show() toolName=$toolName toolTitle=${toolTitle?.take(40)} " +
                "status=${statusText.take(40)} " +
                "running=$isRunning outcome=$outcome reply=${replyExcerpt?.take(20)} " +
                "session=$targetSessionId perm=${hasOverlayPermission()}",
        )
        if (!hasOverlayPermission()) {
            if (isShown) hide()
            return
        }
        mainHandler.post {
            try {
                pendingSessionId = targetSessionId
                if (view == null) {
                    Log.d(TAG, "show() attach() — view was null")
                    attach()
                }
                updateContent(toolName, statusText, isRunning, outcome, replyExcerpt, toolTitle)
            } catch (e: Throwable) {
                Log.w(TAG, "show failed: ${e.message}", e)
            }
        }
    }

        fun hide() {
        mainHandler.post {
            val v = view ?: return@post
            try {
                // [T-overlay-v3-skin] Stop the capsule's frame driver and
                // take the panel with us — an attached panel without its
                // capsule is an orphan window.
                (v as? SessionCapsuleView)?.stopFrameDriver()
                dismissPanel()
                windowManager.removeView(v)
            } catch (e: Throwable) {
                Log.w(TAG, "removeView failed: ${e.message}")
            }
            view = null
            layoutParams = null
            isShown = false
        }
    }

    private fun attach() {
        val container = buildView()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            // [T-overlay-v3-skin] Capsule geometry from the tender-v3
            // render (272x52dp). The old fixed half-screen width/44dp
            // height belonged to the retired LinearLayout pill.
            (SessionCapsuleView.WIDTH_DP * context.resources.displayMetrics.density).toInt(),
            (SessionCapsuleView.HEIGHT_DP * context.resources.displayMetrics.density).toInt(),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // [T-overlay-vivo-fix] Two vivo/Android-15 layer bugs proven
            // live on this device (2026-09-21):
            // (1) Default fitTypes = STATUS_BARS|NAVIGATION_BARS|CAPTION_BAR
            //     made SurfaceFlinger assign the overlay a corrupted layer
            //     (bounds -12600,-28000 → "invisible reason: nothing to
            //     draw") — the window attached, WM said HAS_DRAWN, but the
            //     surface stayed EMPTY (the "fully black window"). The v3
            //     window never hit this because it zeroed fit-insets
            //     (setFitInsetsTypes(0), its "fit-bug note"). Zeroing here
            //     too — an overlay must not fit system bars at all.
            // (2) softInputMode default = adjustPan got the layer the
            //     private flag INSET_PARENT_FRAME_BY_IME (visible in
            //     dumpsys), pinning the surface to the IME parent frame
            //     with a 10-screen buffer. ADJUST_NOTHING: an overlay has
            //     no business tracking the keyboard.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
                setFitInsetsSides(0)
            }
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            // [T-overlay-vivo-fix] FLAG_LAYOUT_NO_LIMITS removed: the
            // position is clamped to the visible display before attach
            // (see below), so the flag's only real effect was letting the
            // window be parked partly off-screen where vivo's nav-bar
            // snapping made it "vanish down" on tap (the user's recorded
            // video bug). On-screen clamping makes the flag pointless.
            gravity = Gravity.TOP or Gravity.START
            val metrics = context.resources.displayMetrics
            val width = (SessionCapsuleView.WIDTH_DP * metrics.density).toInt()
            val height = (SessionCapsuleView.HEIGHT_DP * metrics.density).toInt()
            val landscape = isLandscape()
            val savedX = backgroundRepo.getOverlayX(landscape)
            val savedY = backgroundRepo.getOverlayY(landscape)
            if (savedX >= 0 && savedY >= 0) {
                // [T-overlay-portrait-offscreen] CLAMP the restored position to
                // the CURRENT screen. This window carries FLAG_LAYOUT_NO_LIMITS,
                // so an x/y outside the display is honoured literally — the
                // capsule is placed off-screen and simply never seen. A position
                // saved in LANDSCAPE has an x up to the landscape width;
                // restoring it in PORTRAIT (roughly half as wide) puts the pill
                // past the right edge. That is why the overlay appeared in
                // landscape but "didn't work" in portrait: it was attached and
                // drawing, just outside the visible area.
                //
                // onConfigurationChanged() already clamps, but only for an
                // ALREADY-ATTACHED view — it cannot fix a window that is born
                // off-screen, which is the common case (rotate, then start a
                // turn). Positions are now also stored per orientation, so this
                // clamp is the second line of defence rather than the only one.
                x = savedX.coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
                y = savedY.coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
                if (x != savedX || y != savedY) {
                    Log.i(
                        TAG,
                        "attach: clamped restored position ($savedX,$savedY) -> ($x,$y) for " +
                            "${metrics.widthPixels}x${metrics.heightPixels} landscape=$landscape",
                    )
                    backgroundRepo.setOverlayPosition(x, y, landscape)
                }
            } else {
                // [T-bg-overlay-polish] First-paint default: bottom-left,
                // 10 dp from the left edge and 10 dp above the nav-bar
                // region. We don't have an accurate nav-bar height before
                // attach, so use displayMetrics.heightPixels and trust
                // FLAG_LAYOUT_NO_LIMITS to keep us on-screen.
                val padding = dpToPx(EDGE_PADDING_DP)
                val approxOverlayHeight = ((SessionCapsuleView.HEIGHT_DP + 16f) *
                    context.resources.displayMetrics.density).toInt()
                val approxNavBar = dpToPx(48)
                x = padding
                y = (metrics.heightPixels - approxOverlayHeight - approxNavBar - padding)
                    .coerceAtLeast(0)
            }
        }
        layoutParams = params
        windowManager.addView(container, params)
        view = container
        isShown = true
        // [T-overlay-v3-skin] Birth animation + frame driver from the
        // tender-v3 render; the capsule's own gesture handling replaces
        // the old attachTouchListener (drag/tap/hold all live inside
        // SessionCapsuleView and call back into the legacy mechanics).
        (container as? SessionCapsuleView)?.let { capsule ->
            capsule.playBirth()
            capsule.startFrameDriver()
        }
    }

    private fun buildView(): View {
        // [T-overlay-v3-skin] Legacy LinearLayout/stack composition is
        // retired — see the tail of this function for the v3 capsule
        // construction. Everything above (logo stack, text rows, X) lived
        // only to compose the old pill.
        buildV3CapsuleView()
    }

    private fun buildV3CapsuleView(): View {

        // [T-overlay-v3-skin] The floating window now renders the approved
        // tender-v3 visual (SessionCapsuleView: light waves / breathing /
        // sparks / metrics — pixel-faithful port of tender-v3-phones.png)
        // while KEEPING the legacy controller mechanics this class always
        // had (attach / saved positions / clamping / tap deep-link / X /
        // completion-linger / foreground gating from the service). The old
        // LinearLayout composition (logo + ring + text rows) is retired.
        val capsule = SessionCapsuleView(
            context = context,
            onCapsuleTap = {
                // [T-overlay-legacy-tap] Whole-capsule tap keeps the
                // LEGACY behaviour: open the deep-linked session (or
                // just bring the app forward when no id was published).
                onTap()
            },
        ).apply {
            onDragDelta = { dx, dy ->
                // [T-overlay-legacy-drag] Drag deltas move the window
                // through the SAME clamped path the old touch listener
                // used — the capsule never leaves the reachable screen.
                moveViewBy(dx, dy)
            }
            onDragEnded = {
                // [T-overlay-portrait-offscreen] Clamp before saving (the
                // legacy rule): the stored position must always be a spot
                // the user can reach again.
                persistClampedPosition()
            }
            // [T-overlay-v3-panel] Hold-to-open the sessions panel — the
            // "list of functions" the user asked for. The panel itself is
            // rendered by SessionOverlayPanelView (tender v3 panel design)
            // and anchored above this capsule.
            onHoldComplete = { togglePanel() }
        }
        return capsule
    }

    private fun updateContent(
        toolName: String?,
        statusText: String,
        isRunning: Boolean,
        outcome: ToolOutcome,
        replyExcerpt: String?,
        toolTitle: String?,
    ) {
        // [T-overlay-v3-skin] Content flows into the v3 capsule. The
        // old label/status/reply/glyph rows are retired with the
        // LinearLayout pill. Live = tool running (waves animate);
        // count = sessions (fed by updateSessions, min 1 while shown).
        val capsule = view as? SessionCapsuleView ?: return
        val count = sessionEntries.size.coerceAtLeast(1)
        capsule.update(count = count, live = isRunning, m = sessionMetrics)
    }

    private fun onTap() {
        try {
            val sid = pendingSessionId
            val launchIntent = Intent(
                context,
                Class.forName("com.openminis.app.MainActivity"),
            ).apply {
                // [T-android-overlay-reply-status-34599] When we have a
                // tracked session, route the tap through the existing
                // `minis://session/<id>` deep-link so MainActivity's
                // DeepLinkHandler navigates to that chat. When sid is
                // null (e.g. completion observed before any session was
                // pushed), fall back to plain "bring to front".
                if (!sid.isNullOrBlank()) {
                    data = Uri.parse("minis://session/$sid")
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(launchIntent)
        } catch (e: Throwable) {
            Log.w(TAG, "tap-to-foreground failed: ${e.message}")
        }
        onUserDismiss()
    }

    /**
     * [T-android-overlay-reply-status-34599] Shared dismissal path used
     * by both the X button and the tap-to-open-chat tap. Tears down
     * the window immediately for snappy feedback, then nudges the
     * service-level observer to clear lingered completion state so
     * `shouldShow` flips to false on the next emission and we don't
     * accidentally re-show during a future tool turn that has the
     * same outcome.
     */
    // ---- [T-overlay-v3-skin] v3-capsule glue onto the legacy mechanics ----

    /** Live metrics + session list feeding the capsule and its panel. */
    private var sessionEntries: List<SessionOverlayEntry> = emptyList()
    private var sessionMetrics: SessionOverlayMetrics = SessionOverlayMetrics(0f, 0f, 0, 0)

    /**
     * Feed the running-session list + live process metrics (the service
     * already collects both for the retired v3 window — same data source,
     * new consumer). While the capsule is attached the count badge and
     * the panel update immediately.
     */
    fun updateSessions(entries: List<SessionOverlayEntry>, metrics: SessionOverlayMetrics) {
        sessionEntries = entries
        sessionMetrics = metrics
        val capsule = view as? SessionCapsuleView ?: return
        val live = entries.any { it.live }
        capsule.update(
            count = entries.size.coerceAtLeast(if (isShown) 1 else 0),
            live = live || (isShown && entries.isEmpty()),
            m = metrics,
        )
        panelView?.update(entries, metrics)
    }

    /**
     * [T-overlay-legacy-drag] Move the window by a touch delta, clamped
     * to the reachable screen (the legacy guarantee: the capsule can
     * never be parked where the user cannot grab it again).
     */
    private fun moveViewBy(dx: Float, dy: Float) {
        val params = layoutParams ?: return
        val v = view ?: return
        val dm = context.resources.displayMetrics
        val maxX = (dm.widthPixels - params.width).coerceAtLeast(0)
        val maxY = (dm.heightPixels - params.height).coerceAtLeast(0)
        params.x = (params.x + dx.toInt()).coerceIn(0, maxX)
        params.y = (params.y + dy.toInt()).coerceIn(0, maxY)
        try {
            windowManager.updateViewLayout(v, params)
            repositionPanel()
        } catch (e: Throwable) {
            Log.w(TAG, "updateViewLayout failed: ${e.message}")
        }
    }

    /** Clamp-then-save on drag end (the legacy rule). */
    private fun persistClampedPosition() {
        val params = layoutParams ?: return
        backgroundRepo.setOverlayPosition(params.x, params.y, isLandscape())
    }

    // ---- [T-overlay-v3-panel] sessions panel ("list of functions") ----

    private var panelView: SessionOverlayPanelView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelAttached = false

    private fun togglePanel() {
        if (panelAttached) dismissPanel() else showPanel()
    }

    private fun showPanel() {
        if (panelAttached || !isShown) return
        val entries = sessionEntries.ifEmpty {
            listOf(
                SessionOverlayEntry(
                    sessionId = pendingSessionId.orEmpty(),
                    title = "Minis",
                    startedAtMs = 0L,
                    live = true,
                ),
            )
        }
        val panel = SessionOverlayPanelView(context) { entry ->
            dismissPanel()
            if (entry.sessionId.isNotEmpty()) {
                openSessionById(entry.sessionId)
            }
        }
        panelView = panel
        val dm = context.resources.displayMetrics
        val w = (SessionOverlayPanelView.WIDTH_DP * dm.density).toInt()
        val rows = entries.size.coerceAtLeast(1).coerceAtMost(6)
        val h = (panel.panelHeightDp(rows) * dm.density).toInt()
        val params = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            val anchor = panelAnchorPosition(w, h, dm)
            x = anchor.first
            y = anchor.second
            // [T-overlay-vivo-fix] Same vivo fixes as the capsule.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
                setFitInsetsSides(0)
            }
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        panelParams = params
        try {
            windowManager.addView(panel, params)
            panelAttached = true
            panel.update(entries, sessionMetrics)
            (view as? SessionCapsuleView)?.setPanelOpen(true)
            panel.alpha = 0f
            panel.animate().alpha(1f).setDuration(180).start()
        } catch (e: Throwable) {
            Log.w(TAG, "panel attach failed: ${e.message}")
            panelView = null
        }
    }

    private fun dismissPanel() {
        if (!panelAttached) return
        try {
            panelView?.let { windowManager.removeView(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "panel remove failed: ${e.message}")
        }
        panelView = null
        panelParams = null
        panelAttached = false
        (view as? SessionCapsuleView)?.setPanelOpen(false)
    }

    /** Panel follows the capsule while dragged. */
    private fun repositionPanel() {
        if (!panelAttached) return
        val params = panelParams ?: return
        val dm = context.resources.displayMetrics
        val anchor = panelAnchorPosition(params.width, params.height, dm)
        params.x = anchor.first
        params.y = anchor.second
        try {
            panelView?.let { windowManager.updateViewLayout(it, params) }
        } catch (_: Throwable) {}
    }

    /**
     * Anchor: just above the capsule, horizontally aligned with the
     * capsule's left edge, clamped on screen (tender-v3 panel design).
     */
    private fun panelAnchorPosition(w: Int, h: Int, dm: android.util.DisplayMetrics): Pair<Int, Int> {
        val cp = layoutParams
        val cx = cp?.x ?: 0
        val cy = cp?.y ?: (dm.heightPixels / 2)
        var x = cx
        var y = cy - h - dpToPx(8)
        val maxX = (dm.widthPixels - w).coerceAtLeast(0)
        x = x.coerceIn(0, maxX)
        if (y < 0) y = (cy + (cp?.height ?: 0) + dpToPx(8)).coerceAtMost(dm.heightPixels - h)
        return x to y
    }

    /** Deep-link a session by id (panel row tap → open chat). */
    private fun openSessionById(sessionId: String) {
        try {
            val launchIntent = Intent(
                context,
                Class.forName("com.openminis.app.MainActivity"),
            ).apply {
                data = Uri.parse("minis://session/$sessionId")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(launchIntent)
        } catch (e: Throwable) {
            Log.w(TAG, "openSessionById failed: ${e.message}")
        }
    }

    private fun onUserDismiss() {
        try { onDismissByUser?.invoke() } catch (_: Throwable) {}
        hide()
    }

    /**
     * [T-android-overlay-landscape-width-rotation-drift] Re-clamp the
     * attached capsule into the current screen bounds after a
     * configuration change (orientation flip, multi-window resize). The
     * overlay is a WindowManager view — it is NOT recreated on rotation —
     * so a position saved while in landscape can land off-screen (or fully
     * out of view) once the device returns to portrait. Recompute the
     * width for the new metrics, clamp x/y so the capsule stays fully
     * on-screen, push the new layout, and persist the corrected position.
     *
     * No-op when nothing is attached. Owned by [AgentForegroundService],
     * which forwards its own onConfigurationChanged here.
     */
    fun onConfigurationChanged() {
        mainHandler.post {
            val v = view ?: return@post
            val params = layoutParams ?: return@post
            val dm = context.resources.displayMetrics
            // [T-overlay-v3-skin] Landscape morphs the capsule into the
            // 128dp round window (tender-v3 landscape circle): the
            // window resizes and the capsule animates its own shape.
            val landscape = isLandscape()
            val width: Int
            val height: Int
            if (landscape) {
                val d = (SessionCapsuleView.CIRCLE_WINDOW_DP * dm.density).toInt()
                width = d
                height = d
            } else {
                width = (SessionCapsuleView.WIDTH_DP * dm.density).toInt()
                height = (SessionCapsuleView.HEIGHT_DP * dm.density).toInt()
            }
            (v as? SessionCapsuleView)?.setShape(if (landscape) 1f else 0f)
            repositionPanel()
            val maxX = (dm.widthPixels - width).coerceAtLeast(0)
            val maxY = (dm.heightPixels - height).coerceAtLeast(0)
            val clampedX = params.x.coerceIn(0, maxX)
            val clampedY = params.y.coerceIn(0, maxY)
            if (params.width == width && params.height == height && params.x == clampedX && params.y == clampedY) return@post
            params.width = width
            params.height = height
            params.x = clampedX
            params.y = clampedY
            try {
                windowManager.updateViewLayout(v, params)
                backgroundRepo.setOverlayPosition(clampedX, clampedY, isLandscape())
            } catch (e: Throwable) {
                Log.w(TAG, "onConfigurationChanged relayout failed: ${e.message}")
            }
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * [T-overlay-portrait-offscreen] Current orientation, used to pick the
     * per-orientation saved position. Read from the Configuration rather than
     * comparing display metrics: on a near-square foldable inner screen the
     * width/height comparison flips on a few pixels and we'd thrash between two
     * saved slots.
     */
    private fun isLandscape(): Boolean =
        context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /**
     * Mirrors [AgentForegroundService.toolDisplayLabel] but trims the
     * "Minis is using " prefix — the overlay capsule is tight, so we just
     * show the tool kind ("Shell", "Browser", …).
     */
}
