package com.openminis.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-reasoning-degeneracy] The detector has to catch the loop that was
 * measured in the stored transcript, and it has to stay quiet on ordinary
 * text - a proof uses "=>", code uses "====" and "----", and a list of
 * "=> step one" lines is a list, not a loop.
 *
 * The thresholds were calibrated against the transcript: a sign flood
 * fires after twelve "=> " deltas (24 characters), a repeated line after
 * the third copy of the same line.
 */
class ReasoningDegeneracyTest {

    private val arrow = "⇒"

    /** The line that repeated in the measured transcript. */
    private val loopLine = "**$arrow $arrow ${arrow}${arrow}${arrow}${arrow} $arrow $arrow${arrow} $arrow${arrow} $arrow.**"

    @Test
    fun `a sign flood is caught`() {
        val text = buildString { repeat(12) { append("$arrow ") } }
        val finding = ReasoningDegeneracy.detect(text)
        assertNotNull("the flood must be found", finding)
        assertEquals(ReasoningDegeneracy.Reason.SIGN_FLOOD, finding!!.reason)
        assertTrue(
            "the finding must name the sign, was ${finding.fragment}",
            finding.fragment.contains(arrow),
        )
    }

    @Test
    fun `a repeated line is caught`() {
        val text = listOf(loopLine, loopLine, loopLine).joinToString("\n")
        val finding = ReasoningDegeneracy.detect(text)
        assertNotNull("the repeated line must be found", finding)
        assertEquals(ReasoningDegeneracy.Reason.REPEATED_LINE, finding!!.reason)
        assertTrue(
            "the finding must name the line, was ${finding.fragment}",
            finding.fragment.contains(arrow),
        )
    }

    @Test
    fun `the measured transcript fragment is caught`() {
        // The shape that was in the stored transcript: the same line
        // three times, with a second copy cut short.
        val text = buildString {
            append("I need to check the build now. ")
            append(loopLine).append('\n')
            append(loopLine).append('\n')
            append("$arrow $arrow $arrow *****\"")
        }
        assertNotNull(ReasoningDegeneracy.detect(text))
    }

    @Test
    fun `ordinary prose is not a loop`() {
        val text = "I will read the file, then run the test, and afterwards push the commit. " +
            "The build is green, the tests passed, and the report is in the log."
        assertNull(ReasoningDegeneracy.detect(text))
    }

    @Test
    fun `a proof is not a loop`() {
        val text = "If A then B. $arrow B holds. $arrow therefore C follows, " +
            "and $arrow C gives the result. The sign $arrow is used in proofs."
        assertNull(ReasoningDegeneracy.detect(text))
    }

    @Test
    fun `a list of sign markers is not a loop`() {
        val text = (1..8).joinToString("\n") { "$arrow step $it" }
        assertNull(ReasoningDegeneracy.detect(text))
    }

    @Test
    fun `code separators are not a loop`() {
        val text = buildString {
            append("section one\n")
            append("=".repeat(60))
            append("\nimport kotlin.math.max\n")
            append("-".repeat(60))
            append("\nval x = 1 // a comment\n")
        }
        assertNull(ReasoningDegeneracy.detect(text))
    }

    @Test
    fun `a few repeated signs are not a loop`() {
        // Four signs are not a degeneration - a model may write a short
        // arrow sequence in an ordinary thought.
        assertNull(ReasoningDegeneracy.detect("$arrow $arrow $arrow $arrow"))
    }

    @Test
    fun `a short text is never a loop`() {
        assertNull(ReasoningDegeneracy.detect("ok"))
        assertNull(ReasoningDegeneracy.detect(""))
    }

    @Test
    fun `the flood is found while the text is still small`() {
        // The barrier has to work on a growing stream, not on a finished
        // text: it must fire long before a lot of junk is accumulated.
        val buffer = StringBuilder()
        var firedAt = -1
        for (i in 1..40) {
            buffer.append("$arrow ")
            if (ReasoningDegeneracy.detect(buffer) != null) {
                firedAt = i
                break
            }
        }
        assertTrue("the flood must be caught while streaming", firedAt in 1..16)
        assertTrue(
            "the barrier must fire before a lot of text is collected, was $firedAt deltas",
            firedAt <= 16,
        )
    }
}
