package com.openminis.app.engine

/**
 * [T-reasoning-degeneracy] Catch a model that fell into a token loop.
 *
 * ## What was measured
 *
 * On 25.10 the transcript of the running app was read: 1160 stored messages
 * carried the sign U+21D2, 1124 of them assistant turns, spread over 13
 * sessions and six different models. Not one of them was flagged: the
 * stream interrupt counter stayed at zero and the error column stayed
 * empty. The app had no idea that the text it was showing was a loop.
 *
 * The loop looks like this (a real stored fragment, message of 31334
 * characters with 12857 of those signs - 41 % of the whole text):
 *
 *     **⇒ ⇒ ⇒⇒⇒ ⇒ ⇒⇒ ⇒⇒ ⇒**
 *     **⇒ ⇒ ⇒⇒⇒ ⇒ ⇒⇒ ⇒⇒ ⇒.**
 *
 * The same line, again and again, and the repetitions are in the MIDDLE of
 * the message - in the last 512 characters of that message there were
 * zero signs. A detector that looks at the tail sees nothing.
 *
 * ## Two signatures, and why both are needed
 *
 * 1. A LINE that repeats. The real loop repeats whole lines. In the
 *    sample above the line "**⇒ ⇒ ⇒⇒⇒ ⇒ ⇒⇒ ⇒⇒ ⇒.**" occurs at least
 *    three times.
 *
 * 2. A SIGN that floods. The loop is made of one sign. A sign may also
 *    be used on purpose - as a bullet marker, in a proof - so a sign is
 *    only reported when it fills a large part of a window.
 *
 * A sign alone is not enough: a model that writes "=> " at the start of
 * every thought line is perfectly normal, and that is exactly what the
 * stored transcripts of this very session look like.
 */
internal object ReasoningDegeneracy {

    /** How much of the tail the detector looks at for the sign flood. */
    const val WINDOW_CHARS = 128

    /** A sign has to occur this many times in the window to count. */
    const val MIN_SIGN_OCCURRENCES = 8

    /** And it has to fill this much of the window. */
    const val MIN_SIGN_COVERAGE = 0.20f

    /** How many recent lines the detector looks at for a repeated line. */
    const val LINE_WINDOW = 12

    /** How often the same line has to occur to be a loop. */
    const val MIN_LINE_OCCURRENCES = 3

    /** A line shorter than this is too small to be evidence. */
    const val MIN_LINE_LENGTH = 4

    /**
     * How much of the recent reasoning the detector looks at. The
     * detector needs the recent text only, and the caller must not copy
     * the whole buffer for it - that would be quadratic while the model
     * streams.
     */
    const val TAIL_CHARS = 8192

    /** How much of the broken text the diagnostic excerpt carries. */
    const val EXCERPT_CHARS = 400

    /**
     * Below this many characters there is nothing to judge. Twelve
     * repetitions of a sign are already a flood, and that is 24
     * characters - the barrier must catch the loop while it is still
     * small, not after the text grew.
     */
    const val MIN_TEXT_LENGTH = 24

    /**
     * What the detector found.
     *
     * @param reason what made it a loop
     * @param fragment the piece of text that repeats
     * @param occurrences how many times it occurs
     * @param coverage how much of the window it fills
     */
    data class Finding(
        val reason: Reason,
        val fragment: String,
        val occurrences: Int,
        val coverage: Float,
    )

    /** The two ways a stream can be degenerate. */
    enum class Reason {
        /** The same line keeps coming back. */
        REPEATED_LINE,

        /** One sign floods the text. */
        SIGN_FLOOD,
    }

    /**
     * Looks for a loop in the given text.
     *
     * The text may be the whole reasoning or its tail. Returns null when
     * nothing looks like a loop.
     *
     * The barrier does not use this: it uses [ReasoningLoopWatch], which
     * keeps the evidence of the whole stream. This stays the rule of the
     * short window, on its own tests.
     */
    fun detect(text: CharSequence): Finding? {
        if (text.isEmpty()) return null
        return detectRepeatedLine(text) ?: detectSignFlood(text)
    }

    /**
     * The same line, several times, inside the recent lines.
     *
     * This is the signature the measured loop has: the line is repeated
     * verbatim, which no ordinary reasoning does - a model that writes
     * the same long line three times is not thinking, it is stuck.
     */
    private fun detectRepeatedLine(text: CharSequence): Finding? {
        if (text.length < MIN_LINE_LENGTH) return null
        val lines = text.toString().split('\n')
        if (lines.size < MIN_LINE_OCCURRENCES) return null
        val recent = lines.takeLast(LINE_WINDOW)
        val counts = HashMap<String, Int>()
        for (line in recent) {
            val trimmed = line.trim()
            if (trimmed.length < MIN_LINE_LENGTH) continue
            // A line made of one repeated character is the sign flood,
            // not a repeated line, and it is reported by the other check.
            if (trimmed.toSet().size == 1) continue
            counts[trimmed] = (counts[trimmed] ?: 0) + 1
        }
        var best: Finding? = null
        for ((line, times) in counts) {
            if (times < MIN_LINE_OCCURRENCES) continue
            val coverage = times.toFloat() / recent.size
            if (best == null || coverage > best.coverage) {
                best = Finding(Reason.REPEATED_LINE, line, times, coverage)
            }
        }
        return best
    }

    /**
     * One sign filling the window.
     *
     * A sign is a character that is neither a letter, a digit, a space
     * nor ordinary punctuation - a symbol the model invented or borrowed
     * from a notation. The check needs both a count and a share, so that
     * a sign used as a bullet marker in a long text is not a flood.
     */
    private fun detectSignFlood(text: CharSequence): Finding? {
        if (text.length < MIN_TEXT_LENGTH) return null
        val start = (text.length - WINDOW_CHARS).coerceAtLeast(0)
        val window = text.subSequence(start, text.length)
        val counts = HashMap<Char, Int>()
        for (ch in window) {
            if (!isSymbol(ch)) continue
            counts[ch] = (counts[ch] ?: 0) + 1
        }
        var best: Finding? = null
        for ((symbol, times) in counts) {
            if (times < MIN_SIGN_OCCURRENCES) continue
            val coverage = times.toFloat() / window.length
            if (coverage < MIN_SIGN_COVERAGE) continue
            if (best == null || coverage > best.coverage) {
                best = Finding(Reason.SIGN_FLOOD, symbol.toString(), times, coverage)
            }
        }
        return best
    }

    /**
     * A symbol: not a letter, not a digit, not a space, and not one of
     * the marks that ordinary code and prose repeat on purpose.
     */
    internal fun isSymbol(ch: Char): Boolean {
        if (ch.isLetterOrDigit()) return false
        if (ch.isWhitespace()) return false
        return ch !in ORDINARY_MARKS
    }

    /**
     * Marks that repeat in ordinary text: separators, code punctuation,
     * brackets. A line of them is formatting, not a loop.
     */
    private val ORDINARY_MARKS = setOf(
        '-', '=', '_', '*', '#', '|', '.', ',', ':', ';', '/', '\\', '~',
        '+', '<', '>', '(', ')', '[', ']', '{', '}', '"', '\'', '`', '!', '?',
    )
}
