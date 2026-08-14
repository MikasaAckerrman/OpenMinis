package com.openminis.app.feedback

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.openminis.app.logging.AppLogger

/**
 * [T-completion-haptics] Fires the double-pulse buzz that tells the user a turn
 * is over. Thin shell: every decision about WHETHER to buzz lives in
 * [CompletionFeedbackPolicy]; this class only knows HOW.
 *
 * Deliberate choices:
 *
 *  - **Notification usage, not haptic-feedback usage.** The effect is tagged
 *    `USAGE_NOTIFICATION` so the system routes it through the user's
 *    notification-vibration preference and Do Not Disturb. Tagging it as
 *    touch feedback would make it fire through DND — a "turn finished" buzz at
 *    3am is exactly what DND exists to prevent.
 *  - **No VIBRATE permission needed?** Wrong — `Vibrator.vibrate` requires
 *    `android.permission.VIBRATE`. It is a normal (install-time) permission, so
 *    there is no runtime prompt, but it MUST be in the manifest or every call
 *    throws SecurityException.
 *  - **Every call is wrapped.** A missing vibrator, an OEM that throws on
 *    waveform, a revoked permission — none of these may take down the turn
 *    teardown path they're called from. Failure degrades to "no buzz".
 */
class CompletionHaptics(context: Context) {

    private val appContext = context.applicationContext

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = appContext.getSystemService(VibratorManager::class.java)
                mgr?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "vibrator lookup failed: ${t.message}")
            null
        }
    }

    private val notificationAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    /** True when this device can actually produce the pattern. */
    fun hasVibrator(): Boolean = try {
        vibrator?.hasVibrator() == true
    } catch (t: Throwable) {
        false
    }

    /**
     * Fire the double pulse if [CompletionFeedbackPolicy] says so. Safe to call
     * from any thread and from a `finally` block — never throws.
     */
    fun onTurnEnded(outcome: TurnOutcome, enabled: Boolean, wasActive: Boolean = true) {
        if (!CompletionFeedbackPolicy.shouldVibrate(outcome, enabled, wasActive)) return
        vibrateDoublePulse()
    }

    /**
     * Fire the pattern unconditionally — used by the Settings row so the user
     * can feel exactly what they're enabling before they leave the screen.
     */
    fun vibrateDoublePulse() {
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            val effect = if (v.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(
                    CompletionFeedbackPolicy.DOUBLE_PULSE_TIMINGS,
                    CompletionFeedbackPolicy.DOUBLE_PULSE_AMPLITUDES,
                    NO_REPEAT,
                )
            } else {
                // No amplitude control: the timings array alone alternates
                // off/on, which is the same shape at full strength.
                VibrationEffect.createWaveform(
                    CompletionFeedbackPolicy.DOUBLE_PULSE_TIMINGS,
                    NO_REPEAT,
                )
            }
            v.vibrate(effect, notificationAttributes)
        } catch (t: Throwable) {
            // SecurityException (permission stripped by a ROM), OEM waveform
            // quirks, or a null service under a hostile ROM. A missing buzz is
            // not worth breaking the turn over.
            AppLogger.warning(TAG, "completion vibrate failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "CompletionHaptics"
        private const val NO_REPEAT = -1
    }
}
