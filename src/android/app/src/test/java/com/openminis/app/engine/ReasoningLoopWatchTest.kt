package com.openminis.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-reasoning-degeneracy] The watch has to catch the loops the window
 * detector could not see: a flood in the middle of the reasoning, a flood
 * that is too wide for a dense window, a compact run of one sign and a
 * line that repeats further apart than the old window of twelve lines.
 */
class ReasoningLoopWatchTest {

    private val prose = "The plan is to read the code before changing it, and to prove every claim. "

    private fun feed(watch: ReasoningLoopWatch, text: String, chunk: Int = 16): List<ReasoningDegeneracy.Finding> {
        val found = mutableListOf<ReasoningDegeneracy.Finding>()
        var index = 0
        while (index < text.length) {
            val end = minOf(index + chunk, text.length)
            val finding = watch.accept(text.substring(index, end))
            if (finding != null) found.add(finding)
            index = end
        }
        return found
    }

    @Test
    fun `a flood in the middle is caught although the tail is clean`() {
        val watch = ReasoningLoopWatch()
        val text = prose.repeat(4) + "⇒ ⇒ ⇒⇒ ⇒ ⇒⇒ ⇒⇒ ⇒\n".repeat(6) + prose.repeat(4)
        val found = feed(watch, text)
        assertTrue("the flood in the middle must be found", found.isNotEmpty())
    }

    @Test
    fun `a wide flood in the middle is caught although the tail is clean`() {
        val watch = ReasoningLoopWatch()
        // The measured loop: many dense lines of signs, followed by text
        // that has none. A window that only looks at the tail sees the
        // prose and nothing else.
        val flood = (1..40).joinToString("\n") { "⇒ ⇒ ⇒⇒ ⇒ ⇒⇒ ⇒⇒ ⇒" }
        val text = flood + "\n" + prose.repeat(4)
        val found = feed(watch, text)
        assertTrue("a wide flood must be found", found.isNotEmpty())
    }

    @Test
    fun `a compact run of one sign is caught`() {
        val watch = ReasoningLoopWatch()
        val found = feed(watch, "⇒⇒⇒⇒⇒⇒⇒⇒⇒⇒")
        assertTrue("a compact run must be found", found.isNotEmpty())
        assertEquals(ReasoningDegeneracy.Reason.SIGN_FLOOD, found.first().reason)
    }

    @Test
    fun `a line that repeats further apart than twelve lines is caught`() {
        val watch = ReasoningLoopWatch()
        val filler = (1..20).joinToString("\n") { "ordinary line number $it" }
        val text = "$filler\nthe same thought again\n$filler\nthe same thought again\n$filler\nthe same thought again\n"
        val found = feed(watch, text)
        assertTrue("a repeated line must be found", found.isNotEmpty())
        assertEquals(ReasoningDegeneracy.Reason.REPEATED_LINE, found.first().reason)
    }

    @Test
    fun `three identical lines inside a long reasoning are not a loop`() {
        val watch = ReasoningLoopWatch()
        val text = buildString {
            for (i in 1..120) {
                append("ordinary reasoning line number $i\n")
                if (i % 40 == 0) append("the same checklist item\n")
            }
        }
        val found = feed(watch, text)
        assertTrue(
            "a repeated line in a long reasoning is normal text, found $found",
            found.isEmpty(),
        )
    }

    @Test
    fun `ordinary reasoning with a sign as a marker is not a loop`() {
        val watch = ReasoningLoopWatch()
        val text = buildString {
            for (i in 1..40) {
                append("step ").append(i).append(" ⇒ check the file, then the test\n")
            }
        }
        assertNull("ordinary reasoning must not be flagged", feed(watch, text).firstOrNull())
    }

    @Test
    fun `the measured loop is caught early`() {
        val watch = ReasoningLoopWatch()
        var found: ReasoningDegeneracy.Finding? = null
        var rounds = 0
        while (found == null && rounds < 50) {
            found = watch.accept("⇒ ⇒ ⇒⇒ ⇒ ⇒⇒ ⇒⇒ ⇒\n")
            rounds++
        }
        assertNotNull("the measured loop must be caught", found)
        assertTrue("it must be caught within a few rounds, took $rounds", rounds <= 10)
    }

    @Test
    fun `the last line without a newline is counted once at the end`() {
        val watch = ReasoningLoopWatch()
        watch.accept("first thought")
        watch.accept(" first thought")
        watch.accept(" first thought")
        // The line is still open, so it is not counted yet.
        assertNull(watch.finish())
        val second = ReasoningLoopWatch()
        second.accept("first thought\n")
        second.accept("first thought\n")
        assertNull(second.finish())
        val third = ReasoningLoopWatch()
        third.accept("first thought\n")
        third.accept("first thought\n")
        third.accept("first thought\n")
        assertNotNull("the third occurrence is a loop", third.finish())
    }
}
