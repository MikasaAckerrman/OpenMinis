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

    // ---------------------------------------------------------------- layout
    fun panelHeightDp(rows: Int): Float =
        HEAD_PADDING_V_DP * 2 + 26f + rows * ROW_HEIGHT_DP

    // ------------------------------------------------------------------ touch
    private var pressedRow = -1

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedRow = hitRow(event.x, event.y)
                postInvalidateOnAnimation()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val row = hitRow(event.x, event.y)
                if (row >= 0 && row == pressedRow && row < entries.size) {
                    onRowTap(entries[row])
                } else if (closeRect.contains(event.x, event.y)) {
                    // tap on the header pill / outside rows = close
                    onRowTap(SessionOverlayEntry("", "", 0L, false)) // sentinel → close
                }
                pressedRow = -1
                postInvalidateOnAnimation()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedRow = -1
                postInvalidateOnAnimation()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitRow(x: Float, y: Float): Int {
        for ((idx, rect) in rowBounds) {
            if (rect.contains(x, y)) return idx
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

        // ---- rows ----
        rowBounds.clear()
        var y = headBottom + 1f
        val rowH = dp(ROW_HEIGHT_DP)
        for ((idx, entry) in entries.withIndex()) {
            drawRow(canvas, entry, y, rowH, w, now, pressedRow == idx)
            rowBounds.add(idx to RectF(0f, y, w, y + rowH))
            y += rowH
            if (idx < entries.size - 1) {
                canvas.drawRect(0f, y - 1f, w, y, sepPaint)
            }
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
    }

    private val pressOverlayPaint = Paint().apply { color = Color.argb(8, 255, 255, 255) }
    private val contentRect = RectF()
    private val tmpRect = RectF()
    private val tmpPath = Path()

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
        private const val SAMPLE_INTERVAL_MS = 1500L
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var capsule: SessionCapsuleView? = null
    private var panel: SessionOverlayPanelView? = null
    private var capsuleAttached = false
    private var panelAttached = false
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

    fun shutdown() {
        mainHandler.post {
            dismissPanel()
            detachCapsuleImmediate()
        }
        samplerJob?.cancel()
        samplerJob = null
    }

    // ---------------------------------------------------------------- capsule
    private fun showCapsule() {
        if (capsuleAttached) return
        val view = SessionCapsuleView(context) { onCapsuleTapped() }
        capsule = view
        val dm = context.resources.displayMetrics
        val w = (SessionCapsuleView.WIDTH_DP * dm.density).toInt()
        val h = (SessionCapsuleView.HEIGHT_DP * dm.density).toInt()
        val params = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (BOTTOM_MARGIN_DP * dm.density).toInt()
        }
        try {
            windowManager.addView(view, params)
            capsuleAttached = true
            view.update(entries.size, entries.any { it.live }, lastMetrics)
            view.startFrameDriver()
            view.playBirth()
            startSampler()
            AppLogger.info(TAG, "v3 capsule attached (${entries.size} sessions)")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "v3 capsule attach failed: ${e.message}")
            capsule = null
        }
    }

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
    private fun onCapsuleTapped() {
        if (panelOpen) {
            dismissPanel()
        } else {
            openPanel()
        }
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
        val dm = context.resources.displayMetrics
        val w = (SessionOverlayPanelView.WIDTH_DP * dm.density).toInt()
        val rows = entries.size.coerceAtLeast(1)
        val h = (view.panelHeightDp(rows) * dm.density).toInt()
        val params = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = ((BOTTOM_MARGIN_DP + SessionCapsuleView.HEIGHT_DP + 12f) * dm.density).toInt()
        }
        try {
            windowManager.addView(view, params)
            panelAttached = true
            panelOpen = true
            view.update(entries, lastMetrics)
            capsule?.setPanelOpen(true)
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(180).start()
            AppLogger.info(TAG, "v3 panel opened (${entries.size} rows)")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "v3 panel attach failed: ${e.message}")
            panel = null
        }
    }

    private fun dismissPanel() {
        panelOpen = false
        capsule?.setPanelOpen(false)
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
