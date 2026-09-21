package com.openminis.app.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [T-overlay-v3-stage2] The sessions panel (tender stage 2): opens above
 * the capsule when the capsule is tapped, closes on a second tap or when
 * the last session finishes. Rows show status square (live = accent with
 * glow + pulse), session title, per-session timer / traffic / CPU, a
 * 4-bar equalizer load meter and an "open" affordance; tapping a row
 * deep-links into that session.
 */
class SessionOverlayPanelView(
    context: Context,
    private val onRowTap: (SessionOverlayEntry) -> Unit,
) : View(context) {

    companion object {
        const val WIDTH_DP = 286f
        const val ROW_HEIGHT_DP = 46f
        private const val HEAD_PADDING_H_DP = 14f
        private const val HEAD_PADDING_V_DP = 13f
        private const val RADIUS_DP = 13f
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    var entries: List<SessionOverlayEntry> = emptyList()
        private set
    var totalMetrics: SessionOverlayMetrics = SessionOverlayMetrics(0f, 0f, 0, 0)
        private set

    fun update(list: List<SessionOverlayEntry>, m: SessionOverlayMetrics) {
        entries = list
        totalMetrics = m
        postInvalidateOnAnimation()
    }

    // row hit rects recomputed on draw; tapped in onTouch
    private val rowBounds = ArrayList<Pair<Int, RectF>>()
    private var closeRect = RectF()

    // ---------------------------------------------------------------- paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.PANEL_BG
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = SessionOverlayPalette.PANEL_BORDER
    }
    private val sepPaint = Paint().apply { color = SessionOverlayPalette.ROW_SEPARATOR }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.META_COLOR
    }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF16181D.toInt()
    }
    private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF25272E.toInt()
    }
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.METRIC_LABEL
        textAlign = Paint.Align.CENTER
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.NAME_COLOR
    }
    private val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.META_COLOR
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.IDLE_STATUS
    }
    private val statusGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.ACCENT
    }
    private val statusRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(128, 107, 154, 238)
    }
    private val eqPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.EQ_COLOR
    }
    private val goBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.GO_BG
    }
    private val goBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = SessionOverlayPalette.GO_BORDER
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.GO_ARROW
        style = Paint.Style.FILL
    }
    // [T-overlay-v3-panel-scroll]
    private val scrollBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.METRIC_LABEL
    }
    // [T-overlay-v3-row-hold] progress ring on the go-button.
    private val holdRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = SessionOverlayPalette.ACCENT
    }

    // ---------------------------------------------------------------- layout
    fun panelHeightDp(rows: Int): Float =
        HEAD_PADDING_V_DP * 2 + 26f + rows * ROW_HEIGHT_DP

    // ------------------------------------------------------------------ touch
    private var pressedRow = -1

    // [T-overlay-v3-panel-scroll] vertical scroll when rows exceed the
    // visible window (wheel/drag; content offset clamped).
    private var scrollPx = 0f
    private var maxScrollPx = 0f
    private var lastScrollY = 0f

    // [T-overlay-v3-row-hold] opening a session = HOLD the row (2.5s) with
    // a progress ring on the arrow button; a short tap does NOT open
    // (accidental touch protection, mirrors the orb's 3s hold-to-open).
    private var holdAnimator: ValueAnimator? = null
    private var holdRow = -1
    private var holdSessionId = ""
    private var holdProgress = 0f

    /** Vibrates on hold-complete (set by the window; honors settings). */
    var hapticTick: () -> Unit = {}

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedRow = hitRow(event.x, event.y)
                lastScrollY = event.y
                postInvalidateOnAnimation()
                if (pressedRow >= 0) startRowHold(pressedRow)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastScrollY
                lastScrollY = event.y
                if (kotlin.math.abs(dy) > 2f) {
                    // [T-overlay-v3-row-hold] movement cancels the hold.
                    cancelRowHold()
                    pressedRow = -1
                }
                if (maxScrollPx > 0f) {
                    scrollPx = (scrollPx - dy).coerceIn(0f, maxScrollPx)
                    postInvalidateOnAnimation()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelRowHold()
                // [T-overlay-v3-row-hold] rows open ONLY via the completed
                // 2.5s hold — a short tap deliberately does nothing.
                if (closeRect.contains(event.x, event.y)) {
                    // tap on the header pill / outside rows = close
                    onRowTap(SessionOverlayEntry("", "", 0L, false)) // sentinel → close
                }
                pressedRow = -1
                postInvalidateOnAnimation()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelRowHold()
                pressedRow = -1
                postInvalidateOnAnimation()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun startRowHold(row: Int) {
        cancelRowHold()
        holdRow = row
        // [T-overlay-v3-row-hold] remember the SESSION, not the index:
        // entries can shift under the finger while holding (a session
        // ends, another starts) — opening "whatever is now at index N"
        // would open the wrong chat.
        holdSessionId = entries.getOrNull(row)?.sessionId ?: ""
        holdProgress = 0f
        holdAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2500L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                holdProgress = anim.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (holdProgress >= 0.999f && holdSessionId.isNotEmpty()) {
                        val entry = entries.firstOrNull { it.sessionId == holdSessionId }
                        holdRow = -1
                        holdSessionId = ""
                        holdProgress = 0f
                        hapticTick()
                        postInvalidateOnAnimation()
                        if (entry != null) onRowTap(entry)
                    }
                }
            })
            start()
        }
    }

    private fun cancelRowHold() {
        holdAnimator?.cancel()
        holdAnimator = null
        holdRow = -1
        holdSessionId = ""
        holdProgress = 0f
    }

    private fun hitRow(x: Float, y: Float): Int {
        // [T-overlay-v3-panel-scroll] rowBounds live in the translated
        // content space (drawn with translate(0, -scrollPx)); the finger
        // reports panel-space coords — add the scroll back.
        val cy = y + scrollPx
        for ((idx, rect) in rowBounds) {
            if (rect.contains(x, cy)) return idx
        }
        return -1
    }

    // ------------------------------------------------------------------- draw
    override fun onDraw(canvas: Canvas) {
        val w = measuredWidth.toFloat()
        val h = measuredHeight.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.elapsedRealtime()

        val r = dp(RADIUS_DP)
        contentRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(contentRect, r, r, bgPaint)
        borderPaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(contentRect, r, r, borderPaint)

        // ---- header ----
        val padH = dp(HEAD_PADDING_H_DP)
        val headBottom = dp(HEAD_PADDING_V_DP) + dp(26f)
        titlePaint.textSize = dp(12.5f)
        val accent = SessionOverlayPalette.ACCENT
        titlePaint.color = Color.WHITE
        canvas.drawText("Активные сессии · ", padH, dp(HEAD_PADDING_V_DP) + dp(13f), titlePaint)
        val headW = titlePaint.measureText("Активные сессии · ")
        titlePaint.color = accent
        val countStr = "${entries.size}"
        canvas.drawText(countStr, padH + headW, dp(HEAD_PADDING_V_DP) + dp(13f), titlePaint)
        titlePaint.color = Color.WHITE

        subPaint.textSize = dp(9.5f)
        val subText = "↓${formatKb(totalMetrics.rxKbPerSec)} МБ/с · CPU ${totalMetrics.cpuPercent}% · RAM ${totalMetrics.ramMb}МБ"
        canvas.drawText(subText, padH, dp(HEAD_PADDING_V_DP) + dp(24f), subPaint)

        // total timer pill (right)
        val totalMs = entries.maxOfOrNull { now - it.startedAtMs } ?: 0L
        val timerText = "⌛ ${formatDuration(totalMs)}"
        pillTextPaint.textSize = dp(9f)
        val pillW = pillTextPaint.measureText(timerText) + dp(16f)
        val pillH = dp(20f)
        val pillX = w - padH - pillW
        val pillY = dp(HEAD_PADDING_V_DP) + dp(3f)
        tmpRect.set(pillX, pillY, pillX + pillW, pillY + pillH)
        canvas.drawRoundRect(tmpRect, dp(6f), dp(6f), pillBgPaint)
        canvas.drawRoundRect(tmpRect, dp(6f), dp(6f), pillBorderPaint)
        canvas.drawText(
            timerText,
            pillX + pillW / 2f,
            pillY + pillH / 2f - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2f,
            pillTextPaint,
        )
        closeRect.set(pillX, pillY, pillX + pillW, pillY + pillH)

        // separator under header
        canvas.drawRect(0f, headBottom, w, headBottom + 1f, sepPaint)

        // ---- rows (scrollable: [T-overlay-v3-panel-scroll]) ----
        rowBounds.clear()
        var y = headBottom + 1f
        val rowH = dp(ROW_HEIGHT_DP)
        // Total content height vs visible → scroll budget.
        val contentH = entries.size * rowH
        val visibleH = h - (headBottom + 1f)
        maxScrollPx = (contentH - visibleH).coerceAtLeast(0f)
        if (maxScrollPx <= 0f) scrollPx = 0f
        val saveRows = canvas.save()
        // clip rows to the panel body (below header) and shift by scroll.
        canvas.clipRect(0f, headBottom + 1f, w, h)
        canvas.translate(0f, -scrollPx)
        for ((idx, entry) in entries.withIndex()) {
            drawRow(
                canvas, entry, y, rowH, w, now,
                pressed = pressedRow == idx,
                holdP = if (idx == holdRow) holdProgress else 0f,
            )
            // [T-overlay-v3-panel-scroll] hit-rects are computed in the same
            // translated space the finger uses (hitRow converts back).
            rowBounds.add(idx to RectF(0f, y, w, y + rowH))
            y += rowH
            if (idx < entries.size - 1) {
                canvas.drawRect(0f, y - 1f, w, y, sepPaint)
            }
        }
        canvas.restoreToCount(saveRows)
        // scroll bar when scrollable
        if (maxScrollPx > 0f) {
            val barH = (visibleH * visibleH / contentH).coerceAtLeast(dp(18f))
            val barY = headBottom + 1f + (visibleH - barH) * (scrollPx / maxScrollPx)
            scrollBarPaint.alpha = 90
            canvas.drawRoundRect(
                w - dp(3f), barY, w - dp(1.5f), barY + barH,
                dp(1f), dp(1f), scrollBarPaint,
            )
        }
    }

    private fun drawRow(
        canvas: Canvas,
        entry: SessionOverlayEntry,
        y: Float,
        rowH: Float,
        w: Float,
        now: Long,
        pressed: Boolean,
        holdP: Float = 0f,
    ) {
        if (pressed) {
            canvas.drawRect(0f, y, w, y + rowH, pressOverlayPaint)
        }
        val padH = dp(13f)
        // status square 7dp
        val side = dp(7f)
        val cy = y + rowH / 2f
        val stX = padH
        tmpRect.set(stX, cy - side / 2f, stX + side, cy + side / 2f)
        if (entry.live) {
            statusGlowPaint.setShadowLayer(dp(3.5f), 0f, 0f, Color.argb(150, 107, 154, 238))
            canvas.drawRoundRect(tmpRect, dp(2f), dp(2f), statusGlowPaint)
            // pulse ring
            val phase = ((now % 1900L) / 1900f)
            val ringAlpha = 0.75f * (1f - phase)
            if (ringAlpha > 0.03f) {
                statusRingPaint.alpha = (ringAlpha * 255).toInt()
                statusRingPaint.strokeWidth = dp(1f)
                val grow = lerp(1f, 1.8f, phase)
                val g = side * (grow - 1f) / 2f
                tmpRect.set(stX - g, cy - side / 2f - g, stX + side + g, cy + side / 2f + g)
                canvas.drawRoundRect(tmpRect, dp(3f), dp(3f), statusRingPaint)
            }
        } else {
            canvas.drawRoundRect(tmpRect, dp(2f), dp(2f), statusPaint)
        }

        // name + meta
        val textX = stX + side + dp(11f)
        val goBtnW = dp(22f)
        val eqW = dp(15f)
        val textMaxW = w - textX - goBtnW - eqW - dp(24f)
        namePaint.textSize = dp(11.5f)
        val name = ellipsize(entry.title, namePaint, textMaxW)
        canvas.drawText(name, textX, cy - dp(5f), namePaint)
        metaPaint.textSize = dp(9f)
        val elapsed = formatDuration(now - entry.startedAtMs)
        val meta = if (entry.live) "⌛ $elapsed · работает" else "⌛ $elapsed · ожидание"
        canvas.drawText(ellipsize(meta, metaPaint, textMaxW), textX, cy + dp(7f), metaPaint)

        // equalizer (live only): 4 bars, phase-shifted
        if (entry.live) {
            val eqX = w - goBtnW - dp(8f) - eqW
            val barW = dp(3f)
            for (b in 0 until 4) {
                val phase = ((now / 150f) + b * 0.9f) % 1.7f
                val amp = 0.5f - 0.5f * kotlin.math.cos(2f * Math.PI.toFloat() * (phase / 1.7f))
                val hgt = dp(13f) * lerp(0.55f, 1f, amp) * (0.45f + 0.55f * (b % 4) / 3f)
                val bx = eqX + b * (barW + dp(3f))
                tmpRect.set(bx, cy - hgt / 2f, bx + barW, cy + hgt / 2f)
                eqPaint.alpha = lerp(90f, 255f, amp).toInt()
                canvas.drawRoundRect(tmpRect, dp(1.5f), dp(1.5f), eqPaint)
            }
        }

        // go button (arrow)
        val goX = w - goBtnW - dp(10f)
        tmpRect.set(goX, cy - goBtnW / 2f, goX + goBtnW, cy + goBtnW / 2f)
        canvas.drawRoundRect(tmpRect, dp(6f), dp(6f), goBgPaint)
        canvas.drawRoundRect(tmpRect, dp(6f), dp(6f), goBorderPaint)
        val cx0 = goX + goBtnW / 2f
        val a = dp(4f)
        tmpPath.reset()
        tmpPath.moveTo(cx0 - a * 0.4f, cy - a)
        tmpPath.lineTo(cx0 + a * 0.6f, cy)
        tmpPath.lineTo(cx0 - a * 0.4f, cy + a)
        tmpPath.close()
        canvas.drawPath(tmpPath, arrowPaint)

        // [T-overlay-v3-row-hold] progress ring around the go-button while
        // the 2.5s hold runs — fills clockwise from 12 o'clock.
        if (holdP > 0f) {
            holdRingPaint.strokeWidth = dp(1.8f)
            holdRingPaint.alpha = 255
            holdArcRect.set(
                goX - dp(2f), cy - goBtnW / 2f - dp(2f),
                goX + goBtnW + dp(2f), cy + goBtnW / 2f + dp(2f),
            )
            canvas.drawArc(holdArcRect, -90f, 360f * holdP, false, holdRingPaint)
        }
    }

    private val pressOverlayPaint = Paint().apply { color = Color.argb(8, 255, 255, 255) }
    private val contentRect = RectF()
    private val tmpRect = RectF()
    private val tmpPath = Path()
    private val holdArcRect = RectF()

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var t = text
        while (t.length > 1 && paint.measureText(t + "…") > maxWidth) {
            t = t.dropLast(1)
        }
        return t + "…"
    }

    private fun formatKb(kb: Float): String =
        if (kb >= 1024f) String.format("%.1f", kb / 1024f) else String.format("%.0f", kb / 1024f * 1000) + "к"

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}

/**
 * [T-overlay-v3] Owns the two overlay windows (capsule + panel) for the
 * running-sessions indicator, per the approved tender v3. Replaces the
 * old dots grid. The capsule is shown ONLY while the app is backgrounded
 * and at least one session is running (foreground gate — the "overlay
 * while app open" bug is structurally impossible here: this window class
 * is only attached when [isForegroundGateOpen] reports false).
 */
class SessionOverlayWindow(
    private val context: Context,
    private val scope: CoroutineScope,
    private val isForegroundGateOpen: () -> Boolean,
    private val openSession: (String) -> Unit,
) {
    companion object {
        private const val TAG = "SessionOverlayWindow"
        private const val BOTTOM_MARGIN_DP = 78f

    // [T-overlay-v3-persist] Position + form preferences (screen fractions
    // survive rotation; portrait orb toggle).
    private const val PREFS_NAME = "overlay_v3_prefs"
    private const val PREF_X_FRAC = "x_frac"
    private const val PREF_Y_FRAC = "y_frac"
    private const val PREF_PORTRAIT_ORB = "portrait_orb"
    private const val PREF_HAPTIC = "haptic_enabled"
    private const val PREF_SOUND = "sound_enabled"
        private const val SAMPLE_INTERVAL_MS = 1500L
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * [T-overlay-v3-fit-bug] The VISIBLE app area (excludes nav/gesture
     * bar): raw heightPixels counts the full panel — clamping against it
     * let the user drag the orb into the gesture-bar zone where vivo's
     * fitTypes=NAVIGATION_BARS snap-moved the window (the recorded video
     * bug: "tap and it vanishes down"). Explicit safe margins (24dp top /
     * 80dp bottom / 12dp sides) — currentWindowMetrics.bounds includes the
     * nav bar on API 30+, so it is NOT the visible area.
     */
    private fun visibleBounds(): android.graphics.Rect {
        val dm = context.resources.displayMetrics
        val top = (24f * dm.density).toInt()
        val bottom = (80f * dm.density).toInt()
        val side = (12f * dm.density).toInt()
        return android.graphics.Rect(
            side,
            top,
            (dm.widthPixels - side).coerceAtLeast(1),
            (dm.heightPixels - bottom).coerceAtLeast(top + 1),
        )
    }

    private var capsule: SessionCapsuleView? = null
    private var panel: SessionOverlayPanelView? = null
    private var capsuleAttached = false
    private var panelAttached = false
    // [T-overlay-v3-drag] live layout params of the capsule window (drag).
    private var capsuleParams: WindowManager.LayoutParams? = null

    // [T-overlay-v3-panel-anchor] live params of the panel window.
    private var panelParams: WindowManager.LayoutParams? = null

    // [T-overlay-v3-persist] position/form settings.
    private val overlayPrefs by lazy {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }

    // [T-overlay-v3-feedback] Sound feedback (ToneGenerator — no assets,
    // no audio focus drama). Honors the settings toggle; failures are
    // silently ignored (missing tone service, etc).
    private var toneGenerator: android.media.ToneGenerator? = null
    private fun soundFeedback(tone: Int) {
        if (!overlayPrefs.getBoolean(PREF_SOUND, false)) return
        try {
            if (toneGenerator == null) {
                toneGenerator = android.media.ToneGenerator(
                    android.media.AudioManager.STREAM_SYSTEM, 55,
                )
            }
            toneGenerator?.startTone(tone, 90)
        } catch (_: Throwable) {
            toneGenerator = null
        }
    }
    private var panelOpen = false
    private var samplerJob: Job? = null

    private var entries: List<SessionOverlayEntry> = emptyList()

    // ------------------------------------------------------------------ public
    /** Push the current running-session set. Shows/hides the window. */
    fun updateSessions(list: List<SessionOverlayEntry>) {
        mainHandler.post {
            entries = list
            if (list.isEmpty()) {
                dismissPanel()
                hideCapsule()
            } else {
                if (isForegroundGateOpen()) {
                    // app is foreground → no overlay at all
                    if (capsuleAttached) hideCapsule()
                    return@post
                }
                if (!capsuleAttached) showCapsule() else capsule?.update(
                    list.size,
                    list.any { it.live },
                    lastMetrics,
                )
                if (panelAttached) panel?.update(list, lastMetrics)
            }
        }
    }

    /** Re-check the foreground gate (app went to background / foreground). */
    /**
     * [T-overlay-v3-shape-policy] Rotation: landscape ALWAYS morphs to the
     * orb (fixed); portrait morphs back unless the user forced the orb in
     * settings. The position is re-validated (rotation-safe fractions +
     * reachability guarantee).
     */
    fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        mainHandler.post {
            val landscape = newConfig.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val portraitOrb = overlayPrefs.getBoolean(PREF_PORTRAIT_ORB, false)
            val target = if (landscape || portraitOrb) 1f else 0f
            capsule?.setShape(target, animated = true)
            capsuleParams?.let { params ->
                val dm = context.resources.displayMetrics
                val vb = visibleBounds()
                // Re-anchor from saved fractions so the orb lands at the
                // same RELATIVE spot after rotating.
                restorePosition(vb, params.width, params.height)?.let { (x, y) ->
                    params.x = x
                    params.y = y
                } ?: run {
                    params.x = (vb.width() - params.width) / 2
                    params.y = vb.bottom - params.height -
                        (BOTTOM_MARGIN_DP * dm.density).toInt()
                }
                clampCapsuleIntoReach(params)
                capsule?.let { v ->
                    try {
                        windowManager.updateViewLayout(v, params)
                    } catch (_: Exception) {
                    }
                }
            }
            // Panel re-anchors itself on its next update; close it during
            // the morph to avoid geometry overlap.
            if (panelOpen) dismissPanel()
        }
    }

    fun onForegroundChanged() {
        mainHandler.post {
            if (isForegroundGateOpen()) {
                dismissPanel()
                if (capsuleAttached) hideCapsule()
            } else if (entries.isNotEmpty() && !capsuleAttached) {
                showCapsule()
            }
        }
    }

    /** [T-overlay-v3-feedback] short vibration tick honoring settings. */
    private fun hapticFeedback() {
        if (!overlayPrefs.getBoolean(PREF_HAPTIC, true)) return
        try {
            val vib = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                    as? android.os.VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(android.content.Context.VIBRATOR_SERVICE)
                    as? android.os.Vibrator
            } ?: return
            vib.vibrate(
                android.os.VibrationEffect.createOneShot(12, 70),
            )
        } catch (_: Throwable) {
        }
    }

    fun shutdown() {
        mainHandler.post {
            dismissPanel()
            detachCapsuleImmediate()
        }
        samplerJob?.cancel()
        samplerJob = null
        try {
            toneGenerator?.release()
        } catch (_: Throwable) {
        }
        toneGenerator = null
    }

    /**
     * [T-overlay-v3-soft-clamp] Move by a gesture delta. The window may be
     * parked with up to 45% of its body off screen — but NEVER more: at
     * least 55% stays inside, so it can always be grabbed back. The
     * position is persisted (screen fractions) on every move.
     */
    private fun moveCapsuleBy(dx: Float, dy: Float) {
        val view = capsule ?: return
        val params = capsuleParams ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
        clampCapsuleIntoReach(params)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
        // [T-overlay-v3-prefs-spam] savePosition() used to run on EVERY
        // move event (60/s SharedPreferences writes during a drag) — it
        // now persists only once, when the gesture ends (see
        // notifyDragEnded).
        // [T-overlay-v3-panel-anchor] the panel follows the orb while
        // dragged ("they never moved" — the user's exact complaint).
        if (panelOpen) repositionPanel()
    }

    /** [T-overlay-v3-prefs-spam] One save per gesture, on release. */
    fun notifyDragEnded() {
        if (capsuleAttached) savePosition()
    }

    // ---------------------------------------------------------------- capsule
    private fun showCapsule() {
        if (capsuleAttached) return
        val view = SessionCapsuleView(context) { onCapsuleTapped() }
        capsule = view
        val dm = context.resources.displayMetrics
        // [T-overlay-v3-shape-policy] Shape: landscape is ALWAYS the orb
        // (no override — the user said horizontal can't be changed); in
        // portrait the default is the capsule, but the user can force the
        // orb via the overlay settings toggle.
        val landscape = context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val portraitOrb = overlayPrefs.getBoolean(PREF_PORTRAIT_ORB, false)
        val shapeTarget = if (landscape || portraitOrb) 1f else 0f
        // [T-overlay-v3-trail] the WINDOW in orb mode is 128dp (2x the orb)
        // so the drag trail has room outside the body.
        val wDp = lerpDp(SessionCapsuleView.WIDTH_DP, SessionCapsuleView.CIRCLE_WINDOW_DP, shapeTarget)
        val hDp = lerpDp(SessionCapsuleView.HEIGHT_DP, SessionCapsuleView.CIRCLE_WINDOW_DP, shapeTarget)
        val w = (wDp * dm.density).toInt()
        val h = (hDp * dm.density).toInt()
        val params = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            // [T-overlay-v3-fit-bug] Absolute TOP|LEFT placement so dragging
            // is a plain x/y += delta. FIT TYPES ARE ZEROED: default
            // fitTypes=STATUS_BARS|NAVIGATION_BARS made vivo snap-move the
            // window whenever it crossed the gesture-bar zone (the "tap and
            // it vanishes" video bug). No system-driven repositioning —
            // we own the coordinates.
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0)
            }
            gravity = Gravity.TOP or Gravity.LEFT
            val vb = visibleBounds()
            val saved = restorePosition(vb, w, h)
            if (saved != null) {
                x = saved.first
                y = saved.second
            } else {
                // [T-overlay-v3-fit-bug] default = above the VISIBLE bottom
                // (gesture bar excluded), not raw heightPixels.
                x = (vb.width() - w) / 2
                y = vb.bottom - h - (BOTTOM_MARGIN_DP * dm.density).toInt()
            }
        }
        capsuleParams = params
        view.onDragDelta = { dx, dy -> moveCapsuleBy(dx, dy) }
        view.onDragEnded = { mainHandler.post { notifyDragEnded() } }
        // [T-overlay-v3-hold-open] 3s hold on the orb opens the panel; the
        // short tap path stays "close only".
        view.onHoldComplete = { mainHandler.post { openPanel() } }
        view.onShapeMorph = { progress, targetWDp, targetHDp ->
            mainHandler.post { resizeCapsuleTo(progress, targetWDp, targetHDp) }
        }
        try {
            windowManager.addView(view, params)
            capsuleAttached = true
            view.update(entries.size, entries.any { it.live }, lastMetrics)
            view.startFrameDriver()
            view.setShape(shapeTarget, animated = false)
            view.playBirth()
            startSampler()
            AppLogger.info(TAG, "v3 capsule attached (${entries.size} sessions, shape=$shapeTarget)")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "v3 capsule attach failed: ${e.message}")
            capsule = null
        }
    }

    /** [T-overlay-v3-shape-policy] Resize the capsule window mid-morph. */
    private fun resizeCapsuleTo(progress: Float, wDp: Float, hDp: Float) {
        val view = capsule ?: return
        val params = capsuleParams ?: return
        val dm = context.resources.displayMetrics
        val oldW = view.width
        val oldH = view.height
        val newW = (wDp * dm.density).toInt()
        val newH = (hDp * dm.density).toInt()
        if (newW == oldW && newH == oldH) return
        // [T-overlay-v3-attach-jump] If the view has not been laid out yet
        // (width == 0 — first frames after addView), only update the
        // requested size and DO NOT touch the position: "keeping the center
        // fixed" against a zero size used to teleport the window by
        // (newW-0)/2 pixels into the top-left corner off-screen.
        if (oldW == 0 || oldH == 0) {
            params.width = newW
            params.height = newH
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
            return
        }
        // Keep the CENTER fixed so the morph doesn't jump.
        params.x += (oldW - newW) / 2
        params.y += (oldH - newH) / 2
        params.width = newW
        params.height = newH
        clampCapsuleIntoReach(params)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    /**
     * [T-overlay-v3-soft-clamp] The window may be parked partially off
     * screen (up to 30% of its body hidden) — but never fully stuck: at
     * least 70% of it always stays reachable, and the saved position is
     * re-validated on every show/rotate/resize.
     */
    private fun clampCapsuleIntoReach(params: WindowManager.LayoutParams) {
        val w = params.width.coerceAtLeast(1)
        val h = params.height.coerceAtLeast(1)
        // [T-overlay-v3-fit-bug] Clamp against the VISIBLE bounds and allow
        // at most 30% off-screen (was 45% — the user could shove the orb
        // into the gesture-bar zone and "lose" it). 70% always reachable.
        val vb = visibleBounds()
        val maxX = vb.width() - (w * 0.70f).toInt()
        val maxY = vb.bottom - (h * 0.70f).toInt()
        val minX = -(w * 0.30f).toInt()
        val minY = vb.top - (h * 0.30f).toInt()
        params.x = params.x.coerceIn(minX, maxX.coerceAtLeast(minX))
        params.y = params.y.coerceIn(minY, maxY.coerceAtLeast(minY))
    }

    /** Saved fractional position → pixels, validated against reachability. */
    private fun restorePosition(vb: android.graphics.Rect, winW: Int, winH: Int): Pair<Int, Int>? {
        val fx = overlayPrefs.getFloat(PREF_X_FRAC, -1f)
        val fy = overlayPrefs.getFloat(PREF_Y_FRAC, -1f)
        if (fx < 0f || fy < 0f) return null
        val w = winW
        val h = winH
        val x = (fx * vb.width() - w / 2).toInt()
        val y = (vb.top + fy * vb.height() - h / 2).toInt()
        // [T-overlay-v3-fit-bug] reachability vs the VISIBLE bounds (70%
        // must remain on-screen) — a position saved pre-fix in the gesture
        // bar zone is pulled back instead of restored.
        val maxX = vb.width() - (w * 0.70f).toInt()
        val maxY = vb.bottom - (h * 0.70f).toInt()
        val okX = x in (-(w * 0.30f)).toInt()..maxX.coerceAtLeast(0)
        val okY = y in (-(h * 0.45f)).toInt()..maxY.coerceAtLeast(0)
        if (!okX || !okY) return null // saved spot is unreachable → default
        return x.coerceAtLeast(-(w * 0.45f).toInt()) to y.coerceAtLeast(-(h * 0.45f).toInt())
    }

    /**
     * Persist the center position as screen fractions (rotation-proof).
     * [T-overlay-v3-attach-jump] The live position lives in
     * capsuleParams.x/y — view.x/y are View *translation* properties and
     * are always 0 for WindowManager-managed windows, so reading them here
     * used to persist a garbage fraction (center-left) and teleport the
     * window on the next attach.
     */
    private fun savePosition() {
        val params = capsuleParams ?: return
        val view = capsule ?: return
        // [T-overlay-v3-fit-bug] fractions of the VISIBLE bounds — the same
        // space restorePosition maps them back into.
        val vb = visibleBounds()
        val fx = (params.x + view.width / 2f) / vb.width().coerceAtLeast(1)
        val fy = (params.y + view.height / 2f) / vb.height().coerceAtLeast(1)
        overlayPrefs.edit()
            .putFloat(PREF_X_FRAC, fx.coerceIn(0.05f, 0.95f))
            .putFloat(PREF_Y_FRAC, fy.coerceIn(0.05f, 0.95f))
            .apply()
    }

    private fun lerpDp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun hideCapsule() {
        val view = capsule ?: run { detachCapsuleImmediate(); return }
        if (!capsuleAttached) { detachCapsuleImmediate(); return }
        // [T-overlay-v3-race] The jelly callback must remove THIS view from
        // WindowManager unconditionally. Guarding with `capsule === view`
        // leaked a duplicate capsule: when a new session re-shows the window
        // mid-animation, `capsule` is already the NEW view — the guard
        // skipped the detach and the OLD view stayed attached forever.
        view.playJelly {
            mainHandler.post { detachView(view) }
        }
        // Safety: force-detach if jelly somehow never completes.
        mainHandler.postDelayed({ detachView(view) }, 450)
        capsuleAttached = false
    }

    /**
     * [T-overlay-v3-race] Detach exactly this view; clear the `capsule`
     * bookkeeping only when it is still the current one.
     */
    private fun detachView(view: SessionCapsuleView) {
        try {
            view.stopFrameDriver()
            windowManager.removeView(view)
        } catch (_: Exception) {
            // already removed / not attached — benign
        }
        if (capsule === view) {
            capsule = null
            capsuleAttached = false
            if (entries.isEmpty()) stopSampler()
        }
    }

    private fun detachCapsuleImmediate() {
        capsule?.let {
            try {
                it.stopFrameDriver()
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        capsule = null
        capsuleAttached = false
        if (entries.isEmpty()) stopSampler()
    }

    // ------------------------------------------------------------------ panel
    // [T-overlay-v3-hold-open] Opening the panel = 3s HOLD on the orb.
    // A short tap now ONLY closes an already-open panel (safe dismiss —
    // accidental taps can't cover the screen with the panel).
    private fun onCapsuleTapped() {
        if (panelOpen) dismissPanel()
    }

    private fun openPanel() {
        if (panelAttached || capsuleAttached.not()) return
        val view = SessionOverlayPanelView(context) { entry ->
            if (entry.sessionId.isEmpty()) {
                dismissPanel()
            } else {
                dismissPanel()
                openSession(entry.sessionId)
            }
        }
        panel = view
        view.hapticTick = { hapticFeedback() }
        val dm = context.resources.displayMetrics
        val w = (SessionOverlayPanelView.WIDTH_DP * dm.density).toInt()
        val rows = entries.size.coerceAtLeast(1)
        // [T-overlay-v3-panel-scroll] Cap the panel at 6 visible rows; more
        // sessions scroll inside (the panel view owns scroll gesture + bar).
        val h = (view.panelHeightDp(rows.coerceAtMost(6)) * dm.density).toInt()
        val params = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            // [T-overlay-v3-panel-anchor] The panel is ANCHORED to the
            // capsule (and follows it while dragged — see moveCapsuleBy):
            // sits just above the orb, horizontally aligned to the orb's
            // left edge, clamped on screen.
            gravity = Gravity.TOP or Gravity.LEFT
            val anchor = panelAnchorPosition(w, h, dm)
            x = anchor.first
            y = anchor.second
        }
        panelParams = params
        try {
            windowManager.addView(view, params)
            panelAttached = true
            panelOpen = true
            view.update(entries, lastMetrics)
            capsule?.setPanelOpen(true)
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(180).start()
            soundFeedback(android.media.ToneGenerator.TONE_PROP_BEEP)
            AppLogger.info(TAG, "v3 panel opened (${entries.size} rows)")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "v3 panel attach failed: ${e.message}")
            panel = null
        }
    }

    /**
     * [T-overlay-v3-panel-anchor] Where the panel sits relative to the
     * capsule: above it (fallback below when there's no room up top),
     * aligned to the capsule's left edge, clamped horizontally.
     */
    private fun panelAnchorPosition(
        panelW: Int,
        panelH: Int,
        dm: android.util.DisplayMetrics,
    ): Pair<Int, Int> {
        val capParams = capsuleParams
        val capView = capsule
        val cx = capParams?.x ?: ((dm.widthPixels - panelW) / 2)
        val capW = capView?.width ?: 0
        val capH = capView?.height ?: 0
        val cy = capParams?.y ?: (dm.heightPixels - capH)
        val gap = dpPx(10f)
        // [T-overlay-v3-fit-bug] the panel stays inside the SAFE area too
        // (never over the gesture zone at the bottom).
        val vb = visibleBounds()
        // Prefer ABOVE the capsule; if not enough room, sit BELOW it.
        val aboveY = cy - panelH - gap
        val y = if (aboveY >= vb.top) aboveY else (cy + capH + gap).coerceAtMost(
            vb.bottom - panelH,
        )
        val x = (cx + capW / 2 - panelW / 2)
            .coerceIn(vb.left, (vb.right - panelW).coerceAtLeast(vb.left))
        return x to y
    }

    /** [T-overlay-v3-panel-anchor] Keep the panel glued while dragging. */
    private fun repositionPanel() {
        val view = panel ?: return
        val params = panelParams ?: return
        val dm = context.resources.displayMetrics
        val anchor = panelAnchorPosition(view.width, view.height, dm)
        params.x = anchor.first
        params.y = anchor.second
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun dpPx(dp: Float): Int = (dp * context.resources.displayMetrics.density).toInt()

    private fun dismissPanel() {
        panelOpen = false
        capsule?.setPanelOpen(false)
        soundFeedback(android.media.ToneGenerator.TONE_PROP_NACK)
        val view = panel ?: run { panelAttached = false; return }
        // [T-overlay-v3-race] detach THIS view, not whatever `panel` holds by
        // the time the 150ms fade ends — a fast re-open mid-fade would have
        // installed a new panel the old animation must NOT remove.
        view.animate().alpha(0f).setDuration(150).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                mainHandler.post { detachPanelView(view) }
            }
        }).start()
        panelAttached = false
    }

    /** [T-overlay-v3-race] see dismissPanel. */
    private fun detachPanelView(view: SessionOverlayPanelView) {
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        if (panel === view) {
            panel = null
            panelAttached = false
            panelParams = null
        }
    }

    private fun detachPanelImmediate() {
        panel?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        panel = null
        panelAttached = false
    }

    // ---------------------------------------------------------------- metrics
    private var lastMetrics = SessionOverlayMetrics(0f, 0f, 0, 0)

    private fun startSampler() {
        if (samplerJob?.isActive == true) return
        samplerJob = scope.launch(Dispatchers.IO) {
            var lastRx = -1L
            var lastTx = -1L
            var lastCpuMs = -1L
            var lastStamp = -1L
            while (isActive) {
                try {
                    val now = SystemClock.elapsedRealtime()
                    val rx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
                    val tx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
                    val cpu = readSelfCpuMillis()
                    val ramKb = readSelfRssKb()
                    var rxRate = 0f
                    var cpuPct = 0
                    if (lastStamp > 0 && cpu >= 0 && lastCpuMs >= 0 && now > lastStamp) {
                        val dtSec = (now - lastStamp) / 1000f
                        rxRate = if (lastRx >= 0) ((rx - lastRx) / dtSec) else 0f
                        val dCpu = (cpu - lastCpuMs) / 1000f
                        cpuPct = (dCpu / dtSec * 100f).toInt().coerceIn(0, 400)
                    }
                    lastRx = rx
                    lastTx = tx
                    lastCpuMs = cpu
                    lastStamp = now
                    val m = SessionOverlayMetrics(
                        rxKbPerSec = rxRate,
                        txKbPerSec = if (lastTx >= 0) 0f else 0f,
                        cpuPercent = cpuPct,
                        ramMb = (ramKb / 1024f).toInt(),
                    )
                    withContext(Dispatchers.Main) {
                        lastMetrics = m
                        capsule?.update(entries.size, entries.any { it.live }, m)
                        if (panelAttached) panel?.update(entries, m)
                    }
                } catch (_: Exception) {
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun stopSampler() {
        samplerJob?.cancel()
        samplerJob = null
    }

    /** (utime+stime) in ms from /proc/self/stat — cheap, no Binder. */
    private fun readSelfCpuMillis(): Long = try {
        val stat = File("/proc/self/stat").readText()
        val afterLast = stat.substringAfter(") ")  // skip pid (comm)
        val parts = afterLast.split(" ")
        // field 14/15 (utime/stime) are indices 11/12 after the comm split
        val utime = parts[11].toLong()
        val stime = parts[12].toLong()
        (utime + stime) * 1000L / 100L // USER_HZ = 100 on Android
    } catch (_: Exception) {
        -1L
    }

    /** RSS in KB from /proc/self/statm — cheap, no Binder. */
    private fun readSelfRssKb(): Int = try {
        val statm = File("/proc/self/statm").readText().split(" ")
        (statm[1].toLong() * 4096L / 1024L).toInt()
    } catch (_: Exception) {
        0
    }
}
