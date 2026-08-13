package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * [T-session-rescue] Char budget for the local rescue digest, persisted in
 * SharedPreferences and exposed via the config bridge as
 * `context.rescueDigestMaxChars`.
 *
 * The digest replaces an entire oversized history, so its size IS the
 * post-rescue context floor. 12000 chars ≈ 3K tokens: small enough that any
 * model with a usable window can accept it, large enough to carry the user's
 * asks, a tool ledger and verbatim identifiers. Lower it when even the
 * rescued session won't send (a relay with a hard body cap); raise it when
 * the model has room and you want more detail preserved.
 */
object RescueDigestPrefs {
    private const val PREFS = "minis_context_prefs"
    private const val KEY_MAX_CHARS = "context.rescue.maxchars"

    const val DEFAULT_MAX_CHARS = RescueDigest.DEFAULT_MAX_CHARS
    const val MIN_MAX_CHARS = 2_000
    const val MAX_MAX_CHARS = 60_000

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun maxChars(context: Context): Int =
        prefs(context).getInt(KEY_MAX_CHARS, DEFAULT_MAX_CHARS)
            .coerceIn(MIN_MAX_CHARS, MAX_MAX_CHARS)

    fun setMaxChars(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_MAX_CHARS, value.coerceIn(MIN_MAX_CHARS, MAX_MAX_CHARS))
            .apply()
    }
}

/**
 * [T-session-rescue-refine] Toggle for the second, LLM-based rescue stage.
 *
 * On by default: the model rewrite is strictly better prose when it works, and
 * it cannot make things worse — the local digest is already committed before
 * the call, and a refinement that drops a verbatim fact is rejected. Turn it
 * off to keep rescue fully offline / zero-cost (one small extra request per
 * rescue), or when the provider is known to be unreachable and you don't want
 * to wait for the attempt.
 */
object RescueRefinementPrefs {
    private const val PREFS = "minis_context_prefs"
    private const val KEY_ENABLED = "context.rescue.refine"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}

/**
 * [T-context-maintenance] How often the periodic FULL (LLM) compaction pass is
 * allowed to fire, counted in user sends.
 *
 * This is a cadence FLOOR, not a schedule: the pressure gates in
 * [ContextMaintenance] can run a pass earlier when the window is filling fast,
 * and will skip it entirely on a session too small to repay the request. So
 * raising this number reduces spend without risking a stall; lowering it keeps
 * the context tighter at the cost of more summarisation requests.
 */
object ContextMaintenancePrefs {
    private const val PREFS = "minis_context_prefs"
    private const val KEY_FULL_EVERY_N = "context.maintenance.fulleveryn"

    const val MIN_TURNS = 1
    const val MAX_TURNS = 50

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun fullEveryNTurns(context: Context): Int =
        prefs(context)
            .getInt(KEY_FULL_EVERY_N, ContextMaintenance.DEFAULT_FULL_EVERY_N_TURNS)
            .coerceIn(MIN_TURNS, MAX_TURNS)

    fun setFullEveryNTurns(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_FULL_EVERY_N, value.coerceIn(MIN_TURNS, MAX_TURNS))
            .apply()
    }
}

/**
 * [T-session-rescue] Decides whether a failed turn should be blamed on an
 * oversized session, and therefore whether to point the user at `/rescue`.
 *
 * The hard part is that an over-large request often does NOT come back as a
 * clean "context length exceeded". A body the upstream refuses to finish
 * reading surfaces as a dropped connection, an empty response, or the TTFB
 * watchdog firing ("no response from server") — errors that look like network
 * trouble while the network is fine. So the signal is: an explicit
 * context-size error at ANY size, or a generic/transient failure while the
 * session is already known to be large.
 *
 * Kept Android-free for unit testing.
 */
object RescueAdvisor {
    /** Fraction of the window above which a vague failure is blamed on size. */
    const val LARGE_CONTEXT_FRACTION = 0.6

    private val EXPLICIT_SIZE_MARKERS = listOf(
        "too many tokens", "context length", "max_tokens", "content is too long",
        "exceeds the model", "request too large", "prompt is too long",
        "token limit", "context window", "payload too large", "413",
        "string too long", "too large for",
    )

    private val VAGUE_FAILURE_MARKERS = listOf(
        "no response from server", "empty response", "connection", "closed",
        "reset", "eof", "timeout", "timed out", "stream", "broken pipe",
        "unexpected end", "502", "503", "504", "520", "524",
    )

    fun isExplicitSizeError(message: String): Boolean {
        val m = message.lowercase()
        return EXPLICIT_SIZE_MARKERS.any { m.contains(it) }
    }

    fun isVagueTransportFailure(message: String): Boolean {
        val m = message.lowercase()
        return VAGUE_FAILURE_MARKERS.any { m.contains(it) }
    }

    /**
     * @param contextTokens last reported context size for this session (0 when unknown)
     * @param contextWindow the model's window (0 when unknown)
     */
    fun shouldSuggestRescue(
        errorMessage: String,
        contextTokens: Int,
        contextWindow: Int,
    ): Boolean {
        if (isExplicitSizeError(errorMessage)) return true
        val large = contextWindow > 0 && contextTokens > 0 &&
            contextTokens.toDouble() / contextWindow.toDouble() >= LARGE_CONTEXT_FRACTION
        return large && isVagueTransportFailure(errorMessage)
    }
}
