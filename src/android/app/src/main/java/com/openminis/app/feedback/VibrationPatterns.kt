package com.openminis.app.feedback

/**
 * [T-haptics-customization] The shape of the turn-finished buzz, as user
 * choices rather than one hardcoded array.
 *
 * Split into three independent axes because they answer different complaints:
 * "I don't feel it" is intensity, "I miss it" is length/repetition, and "it's
 * annoying" is both. Composing them beats shipping a flat list of a dozen named
 * patterns nobody can tell apart from the labels.
 *
 * Pure Kotlin — no Android imports — so the composition is unit-testable and
 * [CompletionHaptics] stays a thin shell that only knows how to fire what this
 * produces.
 */
enum class VibrationPattern(val id: String) {
    /** Two short pulses. The original, and still the default. */
    DOUBLE("double"),

    /** One long pulse. Least likely to be mistaken for a keyboard tick. */
    SINGLE_LONG("single_long"),

    /** Three short pulses — hardest to miss in a pocket. */
    TRIPLE("triple"),

    /** Two pulses, second much longer: reads as "…and done". */
    SHORT_LONG("short_long"),

    /** Pulse pair with a tight gap, then a pause, then again. */
    HEARTBEAT("heartbeat"),

    /** One deliberately long, slow buzz for maximum noticeability. */
    LONG_RUMBLE("long_rumble"),
    ;

    companion object {
        val DEFAULT = DOUBLE
        fun fromId(raw: String?): VibrationPattern =
            values().firstOrNull { it.id == raw } ?: DEFAULT
    }
}

/**
 * How hard the motor is driven. Only honoured when the device reports amplitude
 * control; without it Android runs every pulse at full strength.
 */
enum class VibrationIntensity(val id: String, val amplitude: Int) {
    LIGHT("light", 90),
    MEDIUM("medium", 180),
    STRONG("strong", 255),
    ;

    companion object {
        val DEFAULT = MEDIUM
        fun fromId(raw: String?): VibrationIntensity =
            values().firstOrNull { it.id == raw } ?: DEFAULT
    }
}

/** Scales every pulse and gap in the chosen pattern. */
enum class VibrationLength(val id: String, val factor: Float) {
    SHORT("short", 0.6f),
    NORMAL("normal", 1.0f),
    LONG("long", 1.8f),
    EXTRA_LONG("extra_long", 2.6f),
    ;

    companion object {
        val DEFAULT = NORMAL
        fun fromId(raw: String?): VibrationLength =
            values().firstOrNull { it.id == raw } ?: DEFAULT
    }
}

/**
 * A ready-to-fire waveform: [timings] alternating wait/pulse starting with a
 * wait, and [amplitudes] aligned index-for-index (0 on the waits).
 * `VibrationEffect.createWaveform` throws if those two disagree in length, so
 * they are built together and never separately.
 */
class VibrationWaveform(
    val timings: LongArray,
    val amplitudes: IntArray,
) {
    val durationMs: Long get() = timings.sum()
    val pulseCount: Int get() = amplitudes.count { it > 0 }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VibrationWaveform) return false
        return timings.contentEquals(other.timings) &&
            amplitudes.contentEquals(other.amplitudes)
    }

    override fun hashCode(): Int = 31 * timings.contentHashCode() + amplitudes.contentHashCode()
}

/** The user's full haptic preference set. */
data class VibrationProfile(
    val pattern: VibrationPattern = VibrationPattern.DEFAULT,
    val intensity: VibrationIntensity = VibrationIntensity.DEFAULT,
    val length: VibrationLength = VibrationLength.DEFAULT,
    /**
     * When true the buzz is tagged as an ALARM rather than a NOTIFICATION, so it
     * fires even in silent mode / Do Not Disturb.
     *
     * Default false — a completion buzz punching through DND at 3am is exactly
     * what DND exists to prevent. But it is the honest fix for "I never felt it
     * with the screen off": several OEM skins (vivo / OriginOS among them)
     * suppress notification-usage vibration when the ringer is silent or the
     * device is dozing, and no amount of pattern tuning changes that.
     */
    val bypassDnd: Boolean = false,
)

object VibrationPatterns {

    /** Smallest gap that still keeps two pulses distinct on a slow motor. */
    const val MIN_GAP_MS = 20L

    /**
     * Base pulse/gap layout per pattern, in milliseconds, BEFORE the length
     * factor. Index 0 is always a leading wait of 0 so the waveform starts
     * immediately; odd indices are pulses, even indices are gaps.
     */
    private fun baseTimings(pattern: VibrationPattern): LongArray = when (pattern) {
        // The 90ms gap is load-bearing: under ~60ms two pulses smear into one on
        // a slow LRA, over ~150ms they read as two unrelated events.
        VibrationPattern.DOUBLE -> longArrayOf(0, 40, 90, 40)
        VibrationPattern.SINGLE_LONG -> longArrayOf(0, 220)
        VibrationPattern.TRIPLE -> longArrayOf(0, 40, 80, 40, 80, 40)
        VibrationPattern.SHORT_LONG -> longArrayOf(0, 40, 90, 180)
        VibrationPattern.HEARTBEAT -> longArrayOf(0, 55, 60, 55, 220, 55, 60, 55)
        VibrationPattern.LONG_RUMBLE -> longArrayOf(0, 500)
    }

    /**
     * Compose the waveform for [profile].
     *
     * Rounding is guarded: a 0.6 factor on a 40ms pulse yields 24ms, but a
     * shorter base could round to 0 — and a 0ms pulse is a pulse the user never
     * feels while still occupying a slot in the array. Every non-zero entry is
     * floored at 1ms; gaps are floored at [MIN_GAP_MS] so shrinking the pattern
     * can't merge distinct pulses into one blur.
     */
    fun waveform(profile: VibrationProfile): VibrationWaveform {
        val base = baseTimings(profile.pattern)
        val f = profile.length.factor
        val timings = LongArray(base.size)
        val amplitudes = IntArray(base.size)
        for (i in base.indices) {
            val scaled = (base[i] * f).toLong()
            val isPulse = i % 2 == 1
            timings[i] = when {
                base[i] == 0L -> 0L
                isPulse -> scaled.coerceAtLeast(1L)
                else -> scaled.coerceAtLeast(MIN_GAP_MS)
            }
            amplitudes[i] = if (isPulse) profile.intensity.amplitude else 0
        }
        return VibrationWaveform(timings, amplitudes)
    }
}
