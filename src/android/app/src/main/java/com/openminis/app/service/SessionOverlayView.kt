package com.openminis.app.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.openminis.app.logging.AppLogger

/**
 * [T-overlay-v3] Full redesign of the floating session indicator —
 * pixel-faithful port of the approved tender v3 render
 * (tender-v3-phones.png / tender-v3-waves.gif):
 *
 *  ┌─────────────────────────────────────────┐
 *  │ [N] │  ~ ~ ~ blue light waves ~ ~ ~  │ ↓2.4 МБ/с │
 *  │  ▣  │  breathing glow + sparks       │ CPU 18%   │
 *  │     │                                 │ RAM 412МБ │
 *  └─────────────────────────────────────────┘
 *
 * - Dark glass capsule (11dp corner radius), count badge with a pulsing
 *   blue ring, 4 light waves entering from different positions at
 *   different speeds, each BREATHING (alpha + thickness pulse), two
 *   sparks flying through, and a 3-line metrics column (traffic / CPU /
 *   RAM) on the right.
 * - Birth animation: scale .8 → 1.04 → 1 + rise + fade (340 ms).
 * - Jelly dismissal: bulge → bounce → collapse to a point (300 ms).
 * - Press feedback: scale .93 + inner shadow + blue border flash +
 *   8 ms haptic tick.
 * - Tap opens the sessions panel (tender stage 2): list of running
 *   sessions with per-session timer / traffic / CPU and an equalizer
 *   load meter; tap a session row to deep-link into it; tap the capsule
 *   again to close the panel.
 *
 * Everything is drawn with Canvas + ValueAnimator (no Compose — this
 * window lives outside the app process UI). Metrics are sampled on a
 * background thread; only invalidate() touches the UI thread.
 */
object SessionOverlayPalette {
    const val CAPSULE_BG = 0xDD1A1D33.toInt()          // was 0xEE0E0F12: dark indigo glass
    const val CAPSULE_BORDER = 0xFF4A6FD6.toInt()      // was 0xFF25272D: bright blue border
    const val COUNT_BG = 0xFF111426.toInt()
    const val COUNT_BORDER = 0xFF5E7FE5.toInt()
    const val FLOW_BG = 0xFF12162E.toInt()             // was 0xFF0D0E12
    const val FLOW_BORDER = 0xFF4A6FD6.toInt()         // bright blue
    val WAVE_CORE = Color.parseColor("#8BB3FF")
    val WAVE_LIGHT = Color.parseColor("#C2D8FF")
    val WAVE_DIM = Color.argb(140, 80, 140, 255)       // was a=38
    const val SPARK = 0xFF93B8FF.toInt()
    const val METRIC_LABEL = 0xFF7D838E.toInt()
    const val METRIC_VALUE = 0xFFADB3BF.toInt()
    const val METRIC_ICON_BG = 0xFF1D1F26.toInt()
    const val METRIC_ICON_BORDER = 0xFF2A2D34.toInt()
    const val TEXT_PRIMARY = 0xFFE8EAEE.toInt()
    val ACCENT = Color.parseColor("#6B9AEE")          // = WAVE_CORE
    val ACCENT_BRIGHT = Color.parseColor("#8DB2F5")    // = WAVE_TIP
    const val PANEL_BG = 0xF70C0D10.toInt()           // 97%
    const val PANEL_BORDER = 0xFF272930.toInt()
    const val ROW_SEPARATOR = 0xFF191B20.toInt()
    const val NAME_COLOR = 0xFFD3D7DE.toInt()
    const val META_COLOR = 0xFF63676F.toInt()
    const val EQ_COLOR = 0xFF3A3F4B.toInt()
    const val GO_BG = 0xFF1A1C22.toInt()
    const val GO_BORDER = 0xFF2A2D34.toInt()
    const val GO_ARROW = 0xFF8B929E.toInt()
    const val IDLE_STATUS = 0xFF40444C.toInt()
}

/** One running session as shown by the v3 overlay. */
data class SessionOverlayEntry(
    val sessionId: String,
    val title: String,
    val startedAtMs: Long,
    /** true while the session is actively streaming/working. */
    val live: Boolean,
)

/** Live process metrics feeding the capsule's right column. */
data class SessionOverlayMetrics(
    val rxKbPerSec: Float,
    val txKbPerSec: Float,
    val cpuPercent: Int,
    val ramMb: Int,
)

/**
 * The capsule itself. One View, one Canvas pass, driven by a single
 * ValueAnimator at frame rate. touch → press feedback → tap callback.
 */
class SessionCapsuleView(
    context: Context,
    private val onCapsuleTap: () -> Unit,
) : View(context) {

    // [T-overlay-v3-drag] The legacy capsule was draggable ("floating");
    // the tender-v3 capsule keeps that: MOVE deltas are forwarded to the
    // window manager, and the gesture only counts as a tap when the
    // finger stayed within the slop distance.
    /** Drag delta → the window moves the capsule (dx, dy in px). */
    var onDragDelta: (Float, Float) -> Unit = { _, _ -> }

    /** [T-overlay-v3-prefs-spam] fired once when a drag gesture ends. */
    var onDragEnded: (() -> Unit)? = null
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragDistance = 0f
    private val touchSlopPx = 12

    // [T-overlay-v3-landscape-morph] 0f = full capsule (portrait), 1f =
    // full circle (landscape). The window animates the LayoutParams size
    // alongside this progress so the shape morph is one smooth gesture.
    private var shapeMorph = 0f
    private var shapeAnimator: ValueAnimator? = null

    /** Shape progress + target size in dp → the window resizes itself. */
    var onShapeMorph: (progress: Float, wDp: Float, hDp: Float) -> Unit = { _, _, _ -> }

    // [T-overlay-v3-trail] paint for the drag-trail mist.
    private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // [T-overlay-v3-eternal] paints for the inner life of the orb.
    private val liquidArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = SessionOverlayPalette.ACCENT_BRIGHT
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcRect = android.graphics.RectF()

    companion object {
        private const val TAG = "SessionCapsuleView"
        // Geometry (dp) — matches the tender render 1:1.
        const val WIDTH_DP = 272f
        const val HEIGHT_DP = 52f
        // [T-overlay-v3-landscape-morph] landscape circle diameter.
        const val CIRCLE_DP = 64f

        /**
         * [T-overlay-v3-trail] The orb WINDOW is 2x the orb: the outer ring
         * of the window is invisible canvas where the drag-trail mist can
         * live. A 64dp window would clip the trail to nothing — the mist
         * the user asked for would simply never be visible.
         */
        const val CIRCLE_WINDOW_DP = 128f
        private const val RADIUS_DP = 11f
        private const val COUNT_W_DP = 36f
        private const val COUNT_H_DP = 38f
        private const val COUNT_R_DP = 7f
        private const val FLOW_H_DP = 38f
        private const val METRICS_W_DP = 74f
        // Wave descriptors: top dp / widthFraction / periodMs / delayMs / breatheMs
        private val WAVES = listOf(
            WaveSpec(6f, 0.84f, 2200f, 0f, 1150f),
            WaveSpec(14f, 0.66f, 3100f, 450f, 1350f),
            WaveSpec(21f, 0.90f, 1700f, 850f, 950f),
            WaveSpec(30f, 0.58f, 2800f, 250f, 1500f),
        )
        private const val HAPTIC_MS = 8L
    }

    private data class WaveSpec(
        val topDp: Float,
        val widthFraction: Float,
        val periodMs: Float,
        val delayMs: Float,
        val breatheMs: Float,
    )

    // ------------------------------------------------------------------ state
    var sessionCount: Int = 0
        private set
    var metrics: SessionOverlayMetrics = SessionOverlayMetrics(0f, 0f, 0, 0)
        private set
    /** true while ≥1 live session → waves animate; false → frozen dim. */
    var anyLive: Boolean = true
        private set

    private var panelOpen: Boolean = false

    /**
     * [T-overlay-v3-landscape-morph] Morph to the landscape CIRCLE (target=1)
     * or back to the portrait capsule (target=0). 380 ms with a soft
     * overshoot; the window resizes LayoutParams in lockstep via
     * [onShapeMorph] so the whole thing reads as one fluid transformation.
     */
    fun setShape(target: Float, animated: Boolean = true) {
        val t = target.coerceIn(0f, 1f)
        shapeAnimator?.cancel()
        if (!animated) {
            shapeMorph = t
            // [T-overlay-v3-attach-jump] Do NOT fire onShapeMorph here: the
            // window was created with the final size in showCapsule, and
            // the resize callback runs BEFORE the first layout (view.width
            // == 0) — it used to "re-center" the window by
            // (0 - 952px)/2 = -476px, teleporting the capsule into the
            // top-left corner and off-screen on EVERY attach. The morph
            // callback is for ROTATION morphs only.
            postInvalidateOnAnimation()
            return
        }
        shapeAnimator = ValueAnimator.ofFloat(shapeMorph, t).apply {
            duration = 380L
            interpolator = android.view.animation.OvershootInterpolator(0.8f)
            addUpdateListener { anim ->
                shapeMorph = anim.animatedValue as Float
                onShapeMorph(
                    shapeMorph,
                    lerp(WIDTH_DP, CIRCLE_DP, shapeMorph),
                    lerp(HEIGHT_DP, CIRCLE_DP, shapeMorph),
                )
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Snap exactly.
                    shapeMorph = t
                    onShapeMorph(t, lerp(WIDTH_DP, CIRCLE_DP, t), lerp(HEIGHT_DP, CIRCLE_DP, t))
                }
            })
            start()
        }
    }

    fun update(count: Int, live: Boolean, m: SessionOverlayMetrics) {
        sessionCount = count
        anyLive = live
        metrics = m
        postInvalidateOnAnimation()
    }

    fun setPanelOpen(open: Boolean) {
        panelOpen = open
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------- animators
    private val frameDriver: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = null // linear; per-wave phase does the easing
        addUpdateListener { postInvalidateOnAnimation() }
    }

    private var pressAnimator: ValueAnimator? = null
    private var flashAnimator: ValueAnimator? = null
    /** 0..1 progress of the birth (in) / jelly (out) choreography. -1 = none. */
    private var birthProgress = -1f
    private var jellyProgress = -1f

    fun startFrameDriver() {
        if (!frameDriver.isRunning) frameDriver.start()
    }

    fun stopFrameDriver() {
        frameDriver.cancel()
    }

    /** Tender: 340 ms — scale .8→1.04→1, rise 12dp, fade in, glow halo. */
    fun playBirth(onDone: (() -> Unit)? = null) {
        stopDismissAnimators()
        birthProgress = 0f
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            addUpdateListener { anim ->
                birthProgress = anim.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    birthProgress = -1f
                    postInvalidateOnAnimation()
                    onDone?.invoke()
                }
            })
        }
        a.start()
    }

    /** Tender: 300 ms jelly — bulge → bounce → collapse to a point. */
    fun playJelly(onDone: () -> Unit) {
        stopDismissAnimators()
        jellyProgress = 0f
        val scaleX = PropertyValuesHolder.ofFloat("sx", 1f)
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            addUpdateListener { anim ->
                jellyProgress = anim.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    jellyProgress = -1f
                    onDone()
                }
            })
        }
        a.start()
    }

    private fun stopDismissAnimators() {
        birthProgress = -1f
        jellyProgress = -1f
    }

    // ------------------------------------------------------------- geometry
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun density(): Float = resources.displayMetrics.density
    private val contentRect = RectF()

    // [T-overlay-v3-trail] body rect: the visible orb/capsule inside the
    // (possibly larger) window.
    private val bodyRect = RectF()
    private val tmpRect = RectF()
    private val tmpPath = Path()

    // ------------------------------------------------------------------ paint
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.CAPSULE_BG
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = SessionOverlayPalette.CAPSULE_BORDER
    }
    // [T-overlay-v3-press-fill] fill-from-touch fields.
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fillProgress = 0f
    private var fillX = 0f
    private var fillY = 0f
    private val countBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.COUNT_BG
    }
    private val countBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = SessionOverlayPalette.COUNT_BORDER
    }
    private val countTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.TEXT_PRIMARY
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(128, 105, 150, 235) // 50% accent
    }
    private val flowBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.FLOW_BG
    }
    private val flowBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = SessionOverlayPalette.FLOW_BORDER
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.SPARK
    }
    private val metricLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.METRIC_LABEL
    }
    private val metricValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.METRIC_VALUE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val metricIconBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.METRIC_ICON_BG
    }
    private val metricIconBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = SessionOverlayPalette.METRIC_ICON_BORDER
    }
    private val metricIconTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SessionOverlayPalette.METRIC_LABEL
        textAlign = Paint.Align.CENTER
    }

    // ------------------------------------------------------------------ press
    private var pressed = false
    private val vibrator: Vibrator? =
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    // [T-overlay-v3-hold-open] Hold 3s on the orb → the sessions panel
    // opens (sweep ring fills around the orb); a SHORT tap only closes an
    // already-open panel. The window owns the actual open/close actions.
    private var holdAnimator: ValueAnimator? = null
    private var holdProgress = 0f

    /** Hold completed (3s) → window opens the panel. */
    var onHoldComplete: () -> Unit = {}

    private fun startHold() {
        cancelHold()
        holdAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                holdProgress = anim.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (holdProgress >= 0.999f) {
                        holdProgress = 0f
                        postInvalidateOnAnimation()
                        playPressFill(width / 2f, height / 2f)
                        onHoldComplete()
                    }
                }
            })
            start()
        }
    }

    private fun cancelHold() {
        holdAnimator?.cancel()
        holdAnimator = null
        if (holdProgress > 0f) {
            holdProgress = 0f
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // [T-overlay-v3-trail] touches in the invisible trail margin
                // (outside the visible body) pass through — they are not
                // ours to consume.
                if (!isInside(event.x, event.y)) return false
                pressed = true
                playPressFill(event.x, event.y)
                hapticTick()
                postInvalidateOnAnimation()
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragDistance = 0f
                // [T-overlay-v3-hold-open] begin the 3s hold (canceled on
                // move past slop / up / cancel).
                startHold()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragDistance += kotlin.math.abs(dx) + kotlin.math.abs(dy)
                if (dragDistance > touchSlopPx) {
                    // [T-overlay-v3-hold-open] a real drag cancels the hold.
                    cancelHold()
                    // [T-overlay-v3-trail] deposit a fog spot behind the
                    // motion (view-local coords of the PREVIOUS position).
                    addTrailSpot(width / 2f - dx, height / 2f - dy)
                    // [T-overlay-v3-drag] forward only past the slop so
                    // tiny jitters don't move the window.
                    onDragDelta(dx, dy)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val inside = isInside(event.x, event.y)
                pressed = false
                val wasHolding = holdAnimator != null
                val dragged = dragDistance > touchSlopPx
                cancelHold()
                postInvalidateOnAnimation()
                // [T-overlay-v3-prefs-spam] one positional save per gesture.
                if (dragged) onDragEnded?.invoke()
                // Short tap (no drag, no completed hold): toggle-close only.
                if (inside && !dragged && !wasHolding) onCapsuleTap()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                val dragged = dragDistance > touchSlopPx
                cancelHold()
                postInvalidateOnAnimation()
                if (dragged) onDragEnded?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * [T-overlay-v3-trail] Hit-testing against the BODY (the visible
     * orb/capsule), not the full window: in orb mode the window is 128dp
     * with a 64dp body centered in it — taps in the invisible margin
     * (where the trail lives) must not count.
     */
    private fun isInside(x: Float, y: Float): Boolean {
        if (bodyRect.isEmpty) return false
        val pad = dp(6f)
        return x >= bodyRect.left - pad && x <= bodyRect.right + pad &&
            y >= bodyRect.top - pad && y <= bodyRect.bottom + pad
    }

    private fun hapticTick() {
        try {
            vibrator?.vibrate(
                VibrationEffect.createOneShot(
                    HAPTIC_MS,
                    VibrationEffect.DEFAULT_AMPLITUDE,
                ),
            )
        } catch (_: Exception) {
        }
    }

    /**
     * [T-overlay-v3-press-fill] Press feedback = a soft radial FILL that
     * blooms from the touch point INSIDE the body and melts away (~320ms,
     * peak alpha 36 — subtle, clearly felt, no borders/outlines per the
     * user's spec).
     */
    private fun playPressFill(fx: Float, fy: Float) {
        fillX = fx
        fillY = fy
        flashAnimator?.cancel()
        flashAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 320
            addUpdateListener { anim ->
                fillProgress = anim.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fillProgress = 0f
                    postInvalidateOnAnimation()
                }
            })
        }.also { it.start() }
    }

    // ------------------------------------------------------------------- draw
    override fun onDraw(canvas: Canvas) {
        val w = measuredWidth.toFloat()
        val h = measuredHeight.toFloat()
        if (w <= 0f || h <= 0f) return
        val drawT0 = android.os.SystemClock.elapsedRealtime()

        // ---- global transform: birth / jelly / press choreography ----
        var sx = 1f
        var sy = 1f
        var ty = 0f
        var alpha = 1f
        if (birthProgress in 0f..1f) {
            val p = birthProgress
            // scale .8 → 1.04 → 1 (overshoot at 55%)
            sx = if (p < 0.55f) lerp(0.8f, 1.04f, p / 0.55f) else lerp(1.04f, 1f, (p - 0.55f) / 0.45f)
            sy = sx
            ty = lerp(dp(12f), 0f, p)
            alpha = p
        } else if (jellyProgress in 0f..1f) {
            val p = jellyProgress
            // 22%: (1.10, .88) | 48%: (.92, 1.08) | 70%: (.6,.68) | 100%: (.05,.07)
            sx = when {
                p < 0.22f -> lerp(1f, 1.10f, p / 0.22f)
                p < 0.48f -> lerp(1.10f, 0.92f, (p - 0.22f) / 0.26f)
                p < 0.70f -> lerp(0.92f, 0.60f, (p - 0.48f) / 0.22f)
                else -> lerp(0.60f, 0.05f, (p - 0.70f) / 0.30f)
            }
            sy = when {
                p < 0.22f -> lerp(1f, 0.88f, p / 0.22f)
                p < 0.48f -> lerp(0.88f, 1.08f, (p - 0.22f) / 0.26f)
                p < 0.70f -> lerp(1.08f, 0.68f, (p - 0.48f) / 0.22f)
                else -> lerp(0.68f, 0.07f, (p - 0.70f) / 0.30f)
            }
            alpha = if (p < 0.70f) lerp(1f, 0.9f, p / 0.70f) else lerp(0.9f, 0f, (p - 0.70f) / 0.30f)
        } else if (pressed) {
            sx = 0.93f
            sy = 0.93f
        }

        val saveCount = canvas.save()
        canvas.translate(w / 2f, h / 2f + ty)
        canvas.scale(sx, sy)
        canvas.translate(-w / 2f, -h / 2f)
        if (alpha < 1f) {
            bgPaint.alpha = ((Color.alpha(SessionOverlayPalette.CAPSULE_BG)) * alpha).toInt()
        } else {
            bgPaint.alpha = Color.alpha(SessionOverlayPalette.CAPSULE_BG)
        }

        contentRect.set(0f, 0f, w, h)

        // [T-overlay-v3-trail] Fog ONLY as a drag trail: while the orb is
        // being carried, soft blue halos trail BEHIND the motion vector and
        // die out within ~0.7s (see addTrailSpot / drawTrail). No idle fog.
        if (trailSpots.isNotEmpty()) drawTrail(canvas, alpha)

        // ---- body: radius morphs capsule(11dp) → circle(w/2) ----
        val m = shapeMorph
        val r = lerp(dp(RADIUS_DP), minOf(w, h) / 2f, m)
        canvas.drawRoundRect(contentRect, r, r, bgPaint)
        borderPaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(contentRect, r, r, borderPaint)
        // [T-overlay-v3-press-fill] soft radial fill blooming from the
        // touch point — no outlines, just light filling the body.
        if (fillProgress > 0.01f) {
            val fr = maxOf(w, h) * 1.2f
            fillPaint.shader = android.graphics.RadialGradient(
                fillX, fillY, fr,
                (fillProgress * 36).toInt().coerceIn(0, 36).shl(24) or
                    (SessionOverlayPalette.ACCENT and 0xFFFFFF),
                SessionOverlayPalette.ACCENT and 0x00FFFFFF,
                android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(bodyRect, r, r, fillPaint)
        }

        // [T-overlay-v3-landscape-morph] circle content fades IN with the
        // morph, capsule content fades OUT — no crossfading geometry math.
        if (m > 0.02f) drawCircleContent(canvas, w, h, alpha * m)
        if (m < 0.98f) {
            val capAlpha = alpha * (1f - m)
            drawCapsuleContent(canvas, w, h, capAlpha)
        }
        canvas.restoreToCount(saveCount)

        // [lag-visibility] This view redraws EVERY frame while attached
        // (frameDriver → postInvalidateOnAnimation). If the drawing itself
        // is the jank source, frame-drop reports would otherwise say "no
        // markers" and mislead toward GC. RATE-LIMITED: AppLogger.warning
        // can hit synchronous file I/O on this thread, and an unthrottled
        // log per slow frame would AMPLIFY the very jank it measures
        // (120Hz × PrintWriter.flush is a real cost). Ring markers stay
        // cheap (no I/O) but are also throttled to keep other markers in
        // the attribution window.
        val drawDt = android.os.SystemClock.elapsedRealtime() - drawT0
        if (drawDt > 8) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSlowDrawLogMs > 2_000L) {
                lastSlowDrawLogMs = now
                com.openminis.app.logging.AppLogger.warning(
                    "SessionOverlayView",
                    "onDraw took ${drawDt}ms (w=$w h=$h rows=$sessionCount) — over frame budget (rate-limited 2s)",
                )
            }
            if (drawDt > 16 && now - lastSlowDrawMarkMs > 500L) {
                lastSlowDrawMarkMs = now
                com.openminis.app.diagnostics.JankMonitor.mark("overlay onDraw ${drawDt}ms rows=$sessionCount")
            }
        }
    }

    private var lastSlowDrawLogMs = 0L
    private var lastSlowDrawMarkMs = 0L

    /** Capsule-mode content: badge + flow + metrics (fade out on morph). */
    private fun drawCapsuleContent(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        if (alpha <= 0.02f) return
        // [T-overlay-v3-trail] capsule content draws over the BODY rect.
        canvas.clipRect(bodyRect)

        // ---- count badge ----
        val countW = dp(COUNT_W_DP)
        val countH = dp(COUNT_H_DP)
        val countX = dp(10f)
        val countY = (h - countH) / 2f
        tmpRect.set(countX, countY, countX + countW, countY + countH)
        val cr = dp(COUNT_R_DP)
        canvas.drawRoundRect(tmpRect, cr, cr, countBgPaint)
        canvas.drawRoundRect(tmpRect, cr, cr, countBorderPaint)

        // pulsing ring (2.2 s): alpha .75→0, scale 1→1.15
        val ringPhase = ((SystemClock.elapsedRealtime() % 2200L) / 2200f)
        val ringAlpha = (0.75f * (1f - ringPhase) * alpha)
        if (ringAlpha > 0.02f) {
            ringPaint.strokeWidth = dp(1f)
            ringPaint.alpha = (ringAlpha * 255).toInt().coerceIn(0, 255)
            val grow = lerp(1f, 1.15f, ringPhase)
            tmpRect.set(
                countX - (countW * (grow - 1f)) / 2f - dp(4f),
                countY - (countH * (grow - 1f)) / 2f - dp(4f),
                countX + countW + (countW * (grow - 1f)) / 2f + dp(4f),
                countY + countH + (countH * (grow - 1f)) / 2f + dp(4f),
            )
            canvas.drawRoundRect(tmpRect, cr * 1.4f, cr * 1.4f, ringPaint)
        }

        // number
        countTextPaint.textSize = dp(17f)
        countTextPaint.alpha = (alpha * 255).toInt()
        val textY = countY + countH / 2f - (countTextPaint.descent() + countTextPaint.ascent()) / 2f
        canvas.drawText(
            sessionCount.coerceAtMost(99).toString(),
            countX + countW / 2f,
            textY,
            countTextPaint,
        )

        // ---- flow zone with waves ----
        val flowX = countX + countW + dp(10f)
        val metricsW = dp(METRICS_W_DP)
        val flowW = w - flowX - metricsW - dp(12f)
        val flowY = (h - dp(FLOW_H_DP)) / 2f
        tmpRect.set(flowX, flowY, flowX + flowW, flowY + dp(FLOW_H_DP))
        val fr = dp(7f)
        canvas.drawRoundRect(tmpRect, fr, fr, flowBgPaint)
        canvas.drawRoundRect(tmpRect, fr, fr, flowBorderPaint)

        drawWaves(canvas, flowX, flowY, flowW, alpha)

        // ---- metrics column ----
        drawMetrics(canvas, w, h, alpha)
    }

    /**
     * [T-overlay-v3-eternal] Circle content: something alive INSIDE the
     * orb — a breathing core, three orbital sparks on elliptical tracks
     * and two slow rotating "liquid" arcs, all driven by elapsed time so
     * it never stops. The count number rides on top.
     */
    private fun drawCircleContent(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        if (alpha <= 0.02f) return
        val cx = w / 2f
        val cy = h / 2f
        // [T-overlay-v3-trail] base = the BODY radius (64dp orb), not the
        // window: the inner life lives inside the visible orb.
        val base = lerp(minOf(w, h) / 2f, CIRCLE_DP / 2f * density(), shapeMorph)
        val now = SystemClock.elapsedRealtime()

        // ---- rotating "liquid" arcs (visible through the glass) ----
        liquidArcPaint.strokeWidth = dp(2f)
        for (liq in 0..1) {
            val rot = (now % 9000L) / 9000f * 360f * (if (liq == 0) 1f else -1f) + liq * 120f
            val ar = base * (0.66f + 0.05f * Math.sin(now / 1700.0 + liq).toFloat())
            val a = (0.16f + 0.10f * (0.5f + 0.5f * Math.sin(now / 1300.0 + liq * 2.1).toFloat())) * alpha
            liquidArcPaint.alpha = (a * 255).toInt().coerceIn(0, 255)
            arcRect.set(cx - ar, cy - ar * 0.62f, cx + ar, cy + ar * 0.62f)
            val start = rot
            canvas.drawArc(arcRect, start, 150f, false, liquidArcPaint)
        }

        // ---- breathing core ----
        val corePhase = (now % 1800L) / 1800f
        val coreR = base * lerp(0.17f, 0.26f, 0.5f + 0.5f * Math.sin(corePhase * Math.PI * 2.0).toFloat())
        val coreA = (0.55f + 0.25f * Math.sin(corePhase * Math.PI * 2.0).toFloat()) * alpha
        corePaint.shader = android.graphics.RadialGradient(
            cx, cy, coreR,
            (coreA * 255).toInt().coerceIn(0, 255).shl(24) or (SessionOverlayPalette.ACCENT_BRIGHT and 0xFFFFFF),
            SessionOverlayPalette.ACCENT and 0x00FFFFFF,
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, coreR, corePaint)

        // ---- three orbital sparks (different speeds & tilts) ----
        val orbits = floatArrayOf(0.62f, 0.80f, 0.95f)
        val speeds = floatArrayOf(1f, -0.62f, 0.38f)
        val tilts = floatArrayOf(-18f, 24f, 62f)
        for (i in orbits.indices) {
            val t = (now % 10000L) / 10000f
            val ang = t * Math.PI.toFloat() * 2f * speeds[i] * (if (i == 1) 4f else 3f) + i * 2.1f
            val orx = base * orbits[i]
            val ory = base * orbits[i] * 0.66f
            val px = cx + orx * Math.cos(ang.toDouble()).toFloat()
            val py = cy + ory * Math.sin(ang.toDouble()).toFloat()
            val sa = (0.5f + 0.35f * Math.sin(ang * 1.7 + i).toFloat()) * alpha
            sparkPaint.alpha = (sa * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(px, py, dp(1.6f), sparkPaint)
        }

        // ---- count number on top ----
        countTextPaint.textSize = dp(22f)
        countTextPaint.alpha = (alpha * 255).toInt()
        countTextPaint.setShadowLayer(dp(3f), 0f, 0f, SessionOverlayPalette.ACCENT and 0x66FFFFFF)
        val ty = cy - (countTextPaint.descent() + countTextPaint.ascent()) / 2f
        canvas.drawText(sessionCount.coerceAtMost(99).toString(), cx, ty, countTextPaint)
        countTextPaint.clearShadowLayer()

        // ---- [T-overlay-v3-hold-open] 3s hold progress ring around the rim ----
        if (holdProgress > 0f) {
            ringPaint.strokeWidth = dp(2.4f)
            ringPaint.alpha = (alpha * 255).toInt()
            arcRect.set(cx - base + dp(3f), cy - base + dp(3f), cx + base - dp(3f), cy + base - dp(3f))
            canvas.drawArc(arcRect, -90f, 360f * holdProgress, false, ringPaint)
        }
    }

    /**
     * [T-overlay-v3-trail] Drag trail: soft blue radial halos deposited
     * behind the moving orb. Each spot fades (0.7s) and widens as it ages.
     * Optimized: max 10 spots, pure radial gradients, no blur passes —
     * reads as quality mist without touching the frame budget.
     */
    private val trailSpots = ArrayList<TrailSpot>(10)

    private class TrailSpot(val x: Float, val y: Float, val bornAt: Long)

    private fun addTrailSpot(x: Float, y: Float) {
        val now = SystemClock.elapsedRealtime()
        if (trailSpots.isNotEmpty() && now - trailSpots.last().bornAt < 24L) return
        trailSpots.add(TrailSpot(x, y, now))
        while (trailSpots.size > 10) trailSpots.removeAt(0)
    }

    private fun drawTrail(canvas: Canvas, alpha: Float) {
        val now = SystemClock.elapsedRealtime()
        val it = trailSpots.iterator()
        while (it.hasNext()) {
            val spot = it.next()
            val age = (now - spot.bornAt).toFloat()
            if (age > 700f) {
                it.remove()
                continue
            }
            val life = age / 700f
            val a = (1f - life) * 0.16f * alpha
            if (a <= 0.01f) continue
            val r = dp(lerp(10f, 22f, life))
            fogPaint.shader = android.graphics.RadialGradient(
                spot.x, spot.y, r,
                (a * 255).toInt().coerceIn(0, 60).shl(24) or (SessionOverlayPalette.ACCENT and 0xFFFFFF),
                SessionOverlayPalette.ACCENT and 0x00FFFFFF,
                android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(spot.x, spot.y, r, fogPaint)
        }
        // Keep animating while any spot is alive.
        if (trailSpots.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun drawWaves(canvas: Canvas, flowX: Float, flowY: Float, flowW: Float, alpha: Float) {
        val now = SystemClock.elapsedRealtime().toFloat()
        val waveH = dp(2.5f)
        // clip to the flow zone (rounded)
        canvas.save()
        tmpPath.reset()
        tmpPath.addRoundRect(
            tmpRect,
            dp(7f),
            dp(7f),
            Path.Direction.CW,
        )
        canvas.clipPath(tmpPath)

        for (spec in WAVES) {
            // [T-overlay-v3-eternal] Waiting sessions keep SLOW but clearly
            // visible waves (0.28 alpha was "a black platform" to the eye on
            // dark wallpapers — the user's video). Live = full speed.
            val periodMs: Float = if (anyLive) spec.periodMs else spec.periodMs * 1.6f
            // travel: left -55% → 105%
            val travel = ((now + spec.delayMs.toLong()) % periodMs.toLong()) / periodMs
            val left = flowX + flowW * lerp(-0.55f, 1.05f, travel)
            val waveW = flowW * spec.widthFraction
            // breathe: alpha .4→1, thickness .7→1.3
            val breathe = 0.5f - 0.5f * kotlin.math.cos(
                2f * Math.PI.toFloat() *
                    ((now + spec.delayMs.toLong()) % spec.breatheMs.toLong()) / spec.breatheMs,
            )
            val breatheAlpha = lerp(0.55f, 1f, breathe) * (if (anyLive) 1f else 0.90f)
            val thickness = dp(4.0f) * lerp(0.7f, 1.3f, breathe)

            val gradient = LinearGradient(
                left, 0f, left + waveW, 0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    SessionOverlayPalette.WAVE_DIM,
                    SessionOverlayPalette.WAVE_CORE,
                    SessionOverlayPalette.WAVE_LIGHT,
                    SessionOverlayPalette.WAVE_DIM,
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.2f, 0.48f, 0.54f, 0.82f, 1f),
                Shader.TileMode.CLAMP,
            )
            wavePaint.shader = gradient
            wavePaint.alpha = ((breatheAlpha * alpha) * 255).toInt().coerceIn(0, 255)
            // glow: soft shadow layer in the same blue
            wavePaint.setShadowLayer(dp(4f), 0f, 0f, Color.argb(140, 107, 154, 238))
            val y = flowY + dp(spec.topDp)
            tmpRect.set(left, y - thickness / 2f, left + waveW, y + thickness / 2f)
            canvas.drawRoundRect(tmpRect, thickness / 2f, thickness / 2f, wavePaint)
        }

        // sparks: two, 3dp dots crossing the zone
        for (i in 0 until 2) {
            val delay = if (i == 0) 300f else 1500f
            val period = 2700f
            val phase = ((now + delay) % period) / period
            val x = flowX + flowW * lerp(0.10f, 0.96f, phase)
            val y = flowY + dp(if (i == 0) 10f else 25f)
            val visAlpha = when {
                phase < 0.12f -> phase / 0.12f
                phase < 0.50f -> lerp(0.95f, 0.45f, (phase - 0.12f) / 0.38f)
                else -> lerp(0.45f, 0f, (phase - 0.50f) / 0.12f).coerceAtLeast(0f)
            }
            sparkPaint.alpha = ((visAlpha * alpha) * 255).toInt().coerceIn(0, 255)
            sparkPaint.setShadowLayer(dp(3f), 0f, 0f, Color.argb(240, 147, 184, 255))
            canvas.drawCircle(x, y, dp(1.5f), sparkPaint)
        }
        canvas.restore()
    }

    private fun drawMetrics(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val metricsW = dp(METRICS_W_DP)
        val colRight = w - dp(10f)
        val rowH = h / 3f
        val iconSide = dp(11f)
        metricLabelPaint.textSize = dp(7.5f)
        metricValuePaint.textSize = dp(9f)
        metricIconTextPaint.textSize = dp(7.5f)
        metricLabelPaint.alpha = (alpha * 255).toInt()
        metricValuePaint.alpha = (alpha * 255).toInt()
        metricIconBgPaint.alpha = (alpha * 255).toInt()
        metricIconBorderPaint.alpha = (alpha * 255).toInt()

        val rows = listOf(
            Triple("↓", formatRate(metrics.rxKbPerSec), "МБ/с"),
            Triple("C", "${metrics.cpuPercent}", "%"),
            Triple("R", "${metrics.ramMb}", "МБ"),
        )
        for (i in rows.indices) {
            val (icon, value, unit) = rows[i]
            val cy = rowH * i + rowH / 2f
            // icon box
            val iconX = colRight - metricsW
            tmpRect.set(iconX, cy - iconSide / 2f, iconX + iconSide, cy + iconSide / 2f)
            canvas.drawRoundRect(tmpRect, dp(3f), dp(3f), metricIconBgPaint)
            canvas.drawRoundRect(tmpRect, dp(3f), dp(3f), metricIconBorderPaint)
            canvas.drawText(
                icon,
                iconX + iconSide / 2f,
                cy - (metricIconTextPaint.descent() + metricIconTextPaint.ascent()) / 2f,
                metricIconTextPaint,
            )
            // value + unit
            val textX = iconX + iconSide + dp(5f)
            val valY = cy - (metricValuePaint.descent() + metricValuePaint.ascent()) / 2f
            canvas.drawText(value, textX, valY, metricValuePaint)
            val vw = metricValuePaint.measureText(value)
            metricLabelPaint.textSize = dp(6.5f)
            canvas.drawText(unit, textX + vw + dp(2.5f), valY, metricLabelPaint)
            metricLabelPaint.textSize = dp(7.5f)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun formatRate(kbPerSec: Float): String {
        val mb = kbPerSec / 1024f
        return if (mb >= 1f) String.format("%.1f", mb) else String.format("%.2f", mb)
    }

    override fun onDetachedFromWindow() {
        stopFrameDriver()
        pressAnimator?.cancel()
        flashAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
