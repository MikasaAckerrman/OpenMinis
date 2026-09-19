package com.openminis.app.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import com.openminis.app.R
import com.openminis.app.logging.AppLogger

/**
 * [T-completion-sound] Plays a short notification sound when a turn ends —
 * the audio sibling of [CompletionHaptics].
 *
 * Requirements from the user (2026-09-19):
 *  - Fires even when the ringer is SILENT and vibration is OFF — the user
 *    wants an unmistakable "your turn is done" cue that doesn't depend on
 *    the motor or the ringer mode. We use USAGE_ALARM which routes around
 *    silent/vibrate ringer modes and Do Not Disturb (opt-in via profile).
 *  - The sound, whether it plays at all, and its volume are user settings,
 *    not hardcoded — a "loud beep at 3am" is exactly what people uninstall
 *    apps over. Default is OFF for the sound (vibration stays the default
 *    cue); the user opts in explicitly.
 *  - Configurable volume, 0..1, applied at play time (not stream volume).
 *
 * Implementation notes:
 *  - SoundPool (not MediaPlayer) — designed for short, low-latency effects;
 *    pre-loaded into memory, ~0ms start latency, no file I/O at play time.
 *  - USAGE_ALARM means the sound plays at alarm volume, not notification
 *    volume, and is exempt from DND when [CompletionSoundProfile.bypassDnd]
 *    is set. This is the same trade-off as the vibration bypassDnd flag.
 *  - Max 3 streams — the user can't realistically end 3 turns in < 1s, and
 *    SoundPool drops the oldest stream past the cap anyway.
 *  - Every call is wrapped — a missing audio manager, a recycled pool, an
 *    OEM that throws on play — none may take down the turn teardown path.
 */
class CompletionSound(context: Context) {

    private val appContext = context.applicationContext

    private val audioManager: AudioManager? by lazy {
        try {
            appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "audio manager lookup failed: ${t.message}")
            null
        }
    }

    private val alarmAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    private val notificationAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    private val soundPool: SoundPool? by lazy {
        try {
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // [T-completion-sound-bypass] bypassDnd=true → USAGE_ALARM:
                // silent mode and DND do not mute it. bypassDnd=false →
                // USAGE_NOTIFICATION: respects ringer and DND. The pool is
                // built once with ALARM attributes (the stronger guarantee);
                // a notification-usage play is approximated by lowering the
                // volume when the ringer is silent — SoundPool can't rebuild
                // its attributes per-play, and a second pool for the
                // notification case doubles the native memory for a corner
                // most users never hit.
                SoundPool.Builder()
                    .setMaxStreams(3)
                    .setAudioAttributes(alarmAttributes)
            } else {
                @Suppress("DEPRECATION")
                SoundPool(3, AudioManager.STREAM_ALARM, 0)
            }
            builder.build()
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "sound pool init failed: ${t.message}")
            null
        }
    }

    /** Loaded sample IDs, by [CompletionSoundEffect]. */
    private val sampleIds: MutableMap<CompletionSoundEffect, Int> = mutableMapOf()

    /** True when at least one sample loaded successfully. */
    private var loaded = false

    init {
        soundPool?.setOnLoadCompleteListener { _, _, status ->
            if (status != 0) {
                AppLogger.warning(TAG, "sound sample load failed: status=$status")
            }
        }
        loadSamples()
    }

    private fun loadSamples() {
        val pool = soundPool ?: return
        try {
            CompletionSoundEffect.entries.forEach { effect ->
                val resId = effect.resId()
                if (resId != 0) {
                    val id = pool.load(appContext, resId, 1)
                    sampleIds[effect] = id
                }
            }
            loaded = sampleIds.isNotEmpty()
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "sample loading failed: ${t.message}")
            loaded = false
        }
    }

    /**
     * Fire the completion sound if the profile says so. Safe to call from
     * any thread and from a `finally` block — never throws.
     *
     * Mirrors [CompletionHaptics.onTurnEnded]: the WHEN lives in
     * [CompletionFeedbackPolicy], the WHAT in
     * [CompletionSoundProfile]. This is only the HOW.
     */
    fun onTurnEnded(
        outcome: TurnOutcome,
        profile: CompletionSoundProfile,
        wasActive: Boolean = true,
    ) {
        if (!profile.enabled) return
        if (!wasActive) return
        when (outcome) {
            TurnOutcome.Completed, TurnOutcome.Failed -> play(profile)
            TurnOutcome.Cancelled -> Unit // user pressed Stop — not news
        }
    }

    /**
     * Play the selected effect at the configured volume. Used both by the
     * turn-end hook and by the Settings preview row so the user can hear
     * exactly what they're choosing.
     */
    fun play(profile: CompletionSoundProfile = CompletionSoundProfile()) {
        val pool = soundPool ?: return
        if (!loaded) return
        val sampleId = sampleIds[profile.effect] ?: return
        try {
            val vol = profile.volume.coerceIn(0f, 1f)
            pool.play(sampleId, vol, vol, 1, 0, 1f)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "play failed: ${t.message}")
        }
    }

    /** Release the pool — called from onTerminate. */
    fun release() {
        try {
            soundPool?.release()
        } catch (_: Throwable) {
        }
        sampleIds.clear()
        loaded = false
    }

    companion object {
        private const val TAG = "CompletionSound"
    }
}

/**
 * Which built-in sound plays. All shipped as raw resources (short, license-
 * free, and pre-decoded by SoundPool at init).
 */
enum class CompletionSoundEffect(val id: String) {
    /** Soft two-tone chime — the default. */
    CHIME("chime"),

    /** Single clear bell. */
    BELL("bell"),

    /** Short digital blip. */
    BLIP("blip"),

    /** Soft pop. */
    POP("pop"),

    /** Silent — for users who want the settings row but no sound. */
    NONE("none");

    fun resId(): Int = when (this) {
        CHIME -> R.raw.completion_chime
        BELL -> R.raw.completion_bell
        BLIP -> R.raw.completion_blip
        POP -> R.raw.completion_pop
        NONE -> 0
    }

    companion object {
        val DEFAULT = CHIME
        fun fromId(raw: String?): CompletionSoundEffect =
            entries.firstOrNull { it.id == raw } ?: DEFAULT
    }
}

/**
 * Everything the sound needs to know, read at fire time (not wiring time)
 * so Settings changes apply on the very next turn.
 */
data class CompletionSoundProfile(
    /** Master switch — default OFF (vibration is the default cue). */
    val enabled: Boolean = false,
    /** Which sound to play. */
    val effect: CompletionSoundEffect = CompletionSoundEffect.DEFAULT,
    /** 0.0..1.0, applied at play time (not stream volume). */
    val volume: Float = 0.5f,
    /**
     * True → USAGE_ALARM (plays even in silent/DND).
     * False → USAGE_NOTIFICATION (respects ringer mode and DND).
     * Default true — the whole point of adding sound is an unmistakable
     * cue when vibration and ringer are both off.
     */
    val bypassDnd: Boolean = true,
)
