package com.openminis.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-output-arrows] Guards [OutputSanitizer.sanitizeArrows] - the filter that
 * keeps the arrow-run degeneration out of the text the user reads.
 */
class OutputSanitizerTest {

    @Test
    fun `a run of arrows collapses to one`() {
        val out = OutputSanitizer.sanitizeArrows("a ⇒ ⇒ ⇒⇒⇒ ⇒ ⇒⇒ ⇒⇒ ⇒ b")
        assertEquals("a ⇒ b", out)
    }

    @Test
    fun `a tight run of arrows collapses to one`() {
        assertEquals("⇒", OutputSanitizer.sanitizeArrows("⇒⇒⇒"))
    }

    @Test
    fun `a single arrow is never touched`() {
        assertEquals("a ⇒ b", OutputSanitizer.sanitizeArrows("a ⇒ b"))
    }

    @Test
    fun `two arrows are left alone`() {
        assertEquals("a ⇒ ⇒ b", OutputSanitizer.sanitizeArrows("a ⇒ ⇒ b"))
    }

    @Test
    fun `text without arrows is returned unchanged`() {
        val text = "обычный текст без стрелок"
        assertEquals(text, OutputSanitizer.sanitizeArrows(text))
    }

    @Test
    fun `a long degenerate answer is reduced to a single arrow`() {
        val spam = "⇒ ".repeat(200)
        val out = OutputSanitizer.sanitizeArrows(spam)
        assertEquals("⇒ ", out)
    }
}
