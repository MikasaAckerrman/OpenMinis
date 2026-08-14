package com.openminis.app.feedback

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.openminis.app.logging.AppLogger

/**
 * [T-completion-haptics] Fires the buzz that tells the user a turn is over.
 * Thin shell: every decision about WHETHER to buzz lives in
 * [CompletionFeedbackPolicy], every decision about WHAT it feels like lives in
 * [VibrationPatterns]. This class only knows HOW to hand it to the OS.
 *
 * Deliberate choices:
 *
 *  - **Notification usage by default, alarm usage on request.** The effect is
 *    normally tagged `USAGE_NOTIFICATION` so the system routes it through the
 *    user's notification-vibration preference and Do Not Disturb. Tagging it as
 *    touch feedback would make it fire through DND — a "turn finished" buzz at
 *    3am is exactly what DND exists to prevent. But several OEM skins (vivo /
 *    OriginOS among them) suppress notification-usage vibration outright while
 *    the ringer is silent or the device is dozing, which is why a completion
 *    buzz can go unfelt with the screen off. [VibrationProfile.bypassDnd]
 *    switches the tag to `USAGE_ALARM` + `FLAG_BYPASS_INTERRUPTION_POLICY`,
 *    which those skins do honour. Opt-in, because it is a real
 *    interruption-policy override.
 *  - **VibrationAttributes on API 33+.** `vibrate(effect, AudioAttributes)` is
 *    the pre-33 API; that audio bridge is what lets a ROM decide our buzz is
 *    "media-ish" and drop it. The typed overload states the intent directly and
 *    is the only way to pass the bypass flag.
 *  - **VIBRATE permission is required.** Normal (install-time) permission — no
 *    runtime prompt — but `Vibrator.vibrate` throws SecurityException without it.
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

    private val notificationAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    /** Pre-33 fallback for the bypass case: alarm usage is the closest the
     *  audio-attributes bridge gets to "ignore the ringer". */
    private val alarmAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
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
     * Fire the configured pattern if [CompletionFeedbackPolicy] says so. Safe to
     * call from any thread and from a `finally` block — never throws.
     */
    fun onTurnEnded(
        outcome: TurnOutcome,
        enabled: Boolean,
        profile: VibrationProfile = VibrationProfile(),
        wasActive: Boolean = true,
    ) {
        if (!CompletionFeedbackPolicy.shouldVibrate(outcome, enabled, wasActive)) return
        vibrate(profile)
    }

    /**
     * Fire [profile] unconditionally — used by the Settings rows so the user can
     * feel exactly what they're choosing before they leave the screen.
     */
    fun vibrate(profile: VibrationProfile = VibrationProfile()) {
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            val wave = VibrationPatterns.waveform(profile)
            val effect = if (v.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(wave.timings, wave.amplitudes, NO_REPEAT)
            } else {
                // No amplitude control: the timings array alone alternates
                // off/on, which is the same shape at full strength.
                VibrationEffect.createWaveform(wave.timings, NO_REPEAT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, vibrationAttributes(profile.bypassDnd))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(
                    effect,
                    if (profile.bypassDnd) alarmAudioAttributes else notificationAudioAttributes,
                )
            }
        } catch (t: Throwable) {
            // SecurityException (permission stripped by a ROM), OEM waveform
            // quirks, or a null service under a hostile ROM. A missing buzz is
            // not worth breaking the turn over.
            AppLogger.warning(TAG, "completion vibrate failed: ${t.message}")
        }
    }

    /** Backwards-compatible entry point for the default double pulse. */
    fun vibrateDoublePulse() = vibrate(VibrationProfile())

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun vibrationAttributes(bypassDnd: Boolean): VibrationAttributes {
        val builder = VibrationAttributes.Builder()
            .setUsage(
                if (bypassDnd) VibrationAttributes.USAGE_ALARM
                else VibrationAttributes.USAGE_NOTIFICATION,
            )
        if (bypassDnd) {
            builder.setFlags(
                VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY,
                VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY,
            )
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "CompletionHaptics"
        private const val NO_REPEAT = -1
    }
}
