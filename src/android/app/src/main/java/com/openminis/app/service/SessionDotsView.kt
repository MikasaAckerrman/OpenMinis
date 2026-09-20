package com.openminis.app.service

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * [T-overlay-session-dots] Floating-capsule session indicator grid.
 *
 * Replaces the single minis-logo spinner with N per-session round
 * indicators (user spec):
 *  - while a session runs: its dot shows the session's own colour +
 *    first letter and a rotating ring (the old minis spinner look);
 *  - when a session finishes: the capsule label shows the session
 *    NAME across the cell for ~3s (handled by ToolOverlayController),
 *    then the dot plays fill → animated checkmark and disappears.
 *  - up to MAX_DOTS dots fit in the fixed capsule size; the layout
 *    re-flows by count (1 big dot, 2-4 in a row, 5+ as a compact grid);
 *  - each session gets a stable distinct colour derived from its id
 *    (minis-style pastel-on-dark palette).
 */
object SessionDotPalette {
    /** Stable hue from a session id — same session, same colour forever. */
    fun colorFor(sessionId: String): Int {
        var h = 0
        for (c in sessionId) h = 31 * h + c.code
        val hue = abs(h % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.62f, 0.66f))
    }

    /** Softer variant used while the dot is in its resting state. */
    fun softFor(sessionId: String): Int {
        var h = 0
        for (c in sessionId) h = 31 * h + c.code
        val hue = abs(h % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.42f))
    }
}

/**
 * One round per-session indicator. Draws:
 *  - a filled circle in the session colour with the session's initial;
 *  - a rotating arc ring while [running];
 *  - a fill-sweep + drawn checkmark while completing (finish animation).
 */
class SessionDotView(
    context: Context,
    val sessionId: String,
    private val initial: String,
) : View(context) {

    enum class Phase { RUNNING, NAME, FILL_CHECK, GONE }

    private val mainColor = SessionDotPalette.colorFor(sessionId)
    private val softColor = SessionDotPalette.softFor(sessionId)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val checkPath = Path()
    private val checkMeasure = PathMeasure(checkPath, false)
    private val oval = RectF()

    @Volatile var phase: Phase = Phase.RUNNING
        private set

    private var ringStartAngle = 0f
    private var fillSweep = 0f          // 0..360 during FILL_CHECK fill
    private var checkProgress = 0f      // 0..1 during FILL_CHECK check draw
    var textAlpha = 255
        private set

    private var ringAnimator: ValueAnimator? = null
    private var phaseAnimators: AnimatorSet? = null

    private val density = resources.displayMetrics.density

    fun startRunning() {
        cancelAnims()
        phase = Phase.RUNNING
        fillSweep = 0f
        checkProgress = 0f
        textAlpha = 255
        ringAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                ringStartAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        invalidate()
    }

    /**
     * Completion sequence per spec: fill the circle (sweep), then draw the
     * checkmark, hold ~3s, then fade out. [onFinished] fires when the dot
     * is visually done — the controller removes it from the grid then.
     */
    fun playCompletion(onFinished: () -> Unit) {
        cancelAnims()
        phase = Phase.FILL_CHECK

        val fill = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 550
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fillSweep = it.animatedValue as Float
                invalidate()
            }
        }

        // Check path within a unit box — scaled at draw time.
        checkPath.reset()
        checkPath.moveTo(0.26f, 0.52f)
        checkPath.lineTo(0.44f, 0.70f)
        checkPath.lineTo(0.75f, 0.33f)
        checkMeasure.setPath(checkPath, false)

        val check = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 450
            duration = 420
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                checkProgress = it.animatedValue as Float
                invalidate()
            }
        }

        val fadeText = ValueAnimator.ofInt(255, 0).apply {
            duration = 300
            addUpdateListener {
                textAlpha = it.animatedValue as Int
                invalidate()
            }
        }

        val hold = ValueAnimator.ofInt(0, 1).apply { duration = 3000 }

        val fadeOut = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 380
            addUpdateListener {
                alpha = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    phase = Phase.GONE
                    onFinished()
                }
            })
        }

        phaseAnimators = AnimatorSet().apply {
            // fill → hold(3s) → fadeOut sequentially; check + fadeText run
            // in parallel with the early fill (check has its own startDelay).
            playSequentially(fill, hold, fadeOut)
            playTogether(check, fadeText)
            start()
        }
    }

    private fun cancelAnims() {
        ringAnimator?.cancel(); ringAnimator = null
        phaseAnimators?.cancel(); phaseAnimators = null
        alpha = 1f
    }

    fun shutdown() {
        cancelAnims()
    }

    override fun onDetachedFromWindow() {
        cancelAnims()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 4f || h < 4f) return
        val cx = w / 2f
        val cy = h / 2f
        val r = (minOf(w, h) / 2f) - 2.5f

        when (phase) {
            Phase.RUNNING -> {
                // Soft full circle base + strong sweeping arc on top.
                fillPaint.color = softColor
                canvas.drawCircle(cx, cy, r, fillPaint)

                ringPaint.color = mainColor
                oval.set(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(oval, ringStartAngle, 300f, false, ringPaint)

                if (initial.isNotEmpty() && textAlpha > 0) {
                    textPaint.textSize = r * 1.1f
                    textPaint.alpha = textAlpha
                    val ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f
                    canvas.drawText(initial, cx, ty, textPaint)
                }
            }
            Phase.FILL_CHECK -> {
                // Fill sweep: arc grows from -90° filling the circle.
                fillPaint.color = softColor
                canvas.drawCircle(cx, cy, r, fillPaint)
                if (fillSweep > 0f) {
                    fillPaint.color = mainColor
                    oval.set(cx - r, cy - r, cx + r, cy + r)
                    canvas.drawArc(oval, -90f, fillSweep, true, fillPaint)
                }
                // Check drawn with partial progress via path trimming.
                if (checkProgress > 0f) {
                    val seg = checkMeasure.length * checkProgress
                    val dst = Path()
                    checkMeasure.getSegment(0f, seg, dst, true)
                    val sc = (r * 2f) / 1f
                    val save = canvas.save()
                    canvas.translate(cx - r, cy - r)
                    canvas.scale(sc, sc)
                    checkPaint.color = Color.WHITE
                    canvas.drawPath(dst, checkPaint)
                    canvas.restoreToCount(save)
                }
            }
            else -> Unit
        }
    }
}

/**
 * Adaptive grid of session dots inside the capsule's fixed dot zone.
 * Reflows by count: 1 big dot / 2-4 row / 5+ compact two-row grid,
 * MAX 10 visible (spec: "как минимум 10 индикаторов").
 */
class SessionDotsGrid(context: Context) : LinearLayout(context) {

    companion object {
        const val MAX_DOTS = 10
        private const val TAG = "SessionDotsGrid"
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    /** sessionId -> dot */
    private val dots = LinkedHashMap<String, SessionDotView>()
    /** Finishing dots awaiting removal (kept so the animation completes). */
    private val finishing = LinkedHashMap<String, SessionDotView>()
    private var rebuildPending = false

    fun updateRunning(running: List<Pair<String, String>>) {
        // Add/update running dots
        val seen = HashSet<String>()
        for ((sid, initial) in running) {
            seen.add(sid)
            if (!dots.containsKey(sid) && !finishing.containsKey(sid)) {
                addDot(sid, initial)
            }
        }
        // Sessions that stopped running without a completion event:
        // keep them (they may transition to finishing) — nothing to do.
        requestRebuild()
    }

    /**
     * Enqueue the completion animation for [sessionId]; [onCellGone] fires
     * when the whole fill/check/fade sequence has finished.
     */
    fun completeSession(sessionId: String, onCellGone: () -> Unit) {
        val dot = dots.remove(sessionId) ?: finishing[sessionId] ?: run {
            requestRebuild(); return
        }
        finishing[sessionId] = dot
        requestRebuild()
        dot.playCompletion {
            finishing.remove(sessionId)
            requestRebuild()
            onCellGone()
        }
    }

    fun hasAnyDot(): Boolean = dots.isNotEmpty() || finishing.isNotEmpty()

    fun runningCount(): Int = dots.size

    private fun addDot(sessionId: String, initial: String) {
        if (dots.size + finishing.size >= MAX_DOTS) return
        val dot = SessionDotView(context, sessionId, initial.take(1).uppercase())
        dot.startRunning()
        dots[sessionId] = dot
    }

    private fun requestRebuild() {
        if (rebuildPending) return
        rebuildPending = true
        post {
            rebuildPending = false
            rebuild()
        }
    }

    private fun rebuild() {
        removeAllViews()
        // [T-overlay-session-dots-crash] Cached dots (kept alive for the
        // completion animation) still carry a parent pointer to their
        // previous row/column wrapper — that wrapper was detached above,
        // but the parent reference itself survives. Re-adding a view
        // that still has a parent throws IllegalStateException ("The
        // specified child already has a parent") — this was the crash
        // loop on every rebuild after the first one. Detach explicitly.
        (dots.values + finishing.values).forEach { dot ->
            (dot.parent as? ViewGroup)?.removeView(dot)
        }
        val all = dots.values + finishing.values
        val n = all.size
        if (n == 0) return
        val (dotSize, perRow) = when {
            n == 1 -> dp(26) to 1
            n in 2..4 -> dp(18) to 4
            else -> dp(13) to 5
        }
        val gap = dp(3)
        orientation = if (perRow == 1) VERTICAL else HORIZONTAL
        // Two-row grid: wrap into vertical columns of rows.
        fun lp(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
            marginStart = gap / 2
            marginEnd = gap / 2
            gravity = Gravity.CENTER_VERTICAL
        }

        if (perRow == 1) {
            val col = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
            all.forEach { col.addView(it, lp()) }
            addView(col, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        } else {
            // vertical wrapper of rows
            val wrap = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            all.chunked(perRow).forEach { rowDots ->
                val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
                rowDots.forEach { row.addView(it, lp()) }
                wrap.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            addView(wrap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        Log.d(TAG, "rebuild: n=$n size=$dotSize perRow=$perRow")
    }

    fun shutdownAll() {
        dots.values.forEach { it.shutdown() }
        finishing.values.forEach { it.shutdown() }
        dots.clear()
        finishing.clear()
        removeAllViews()
    }
}
