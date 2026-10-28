package com.openminis.app.engine

/**
 * [T-output-arrows] Strips the arrow-run degeneration from the text the
 * agent shows to the user.
 *
 * Why this exists: the model sometimes degenerates into long runs of
 * "⇒" (the same shape [ReasoningDegeneracy] detects in reasoning text:
 * "⇒ ⇒ ⇒⇒⇒ ⇒ ⇒⇒ ⇒⇒ ⇒"). A run of arrows carries no information, it
 * only bloats the message and the context window, and it reads as a bug
 * to the user.
 *
 * The rule is deliberately conservative: only runs of three or more
 * arrows are collapsed, and a single arrow a user typed is never
 * touched. Two arrows are left alone as well, because "a ⇒ b" is a
 * normal way to write a step and the user's own text must survive
 * unchanged.
 */
object OutputSanitizer {

    /** Three or more arrows, optionally separated by whitespace. */
    private val ARROW_RUN = Regex("⇒(?:\\s*⇒){2,}")

    /** A run that ends with a trailing arrow but no separator, e.g. "⇒⇒⇒". */
    private val ARROW_RUN_TIGHT = Regex("(?:⇒){3,}")

    /**
     * Collapses arrow runs into a single arrow.
     *
     * - "⇒ ⇒ ⇒⇒⇒ ⇒ ⇒⇒ ⇒⇒ ⇒" becomes "⇒ ".
     * - "a ⇒ b" is unchanged.
     * - "⇒⇒⇒" becomes "⇒".
     */
    fun sanitizeArrows(text: String): String {
        if (!text.contains('⇒')) return text
        val collapsed = ARROW_RUN.replace(text) { "⇒ " }
        return ARROW_RUN_TIGHT.replace(collapsed) { "⇒" }
    }
}
