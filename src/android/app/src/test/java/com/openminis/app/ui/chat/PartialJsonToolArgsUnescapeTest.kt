package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-gc-churn] `unescape` used to be five chained `replace()` calls, which
 * both allocated five Strings on every call and produced wrong text: a
 * literal backslash followed by 'n' (which in JSON is an escaped backslash
 * and then the letter n) matched the `\n` rule first and became a real
 * newline. A single left-to-right pass consumes each escape exactly once.
 */
class PartialJsonToolArgsUnescapeTest {

    private fun extractValue(valueBody: String): String? =
        PartialJsonToolArgs.extract("path", "{\"path\": \"$valueBody\"}")

    @Test
    fun plainValueIsReturnedUnchanged() {
        assertEquals("/tmp/out.txt", extractValue("/tmp/out.txt"))
    }

    @Test
    fun standardEscapesAreDecoded() {
        assertEquals("a\nb", extractValue("a\\nb"))
        assertEquals("a\tb", extractValue("a\\tb"))
        assertEquals("say \"hi\"", extractValue("say \\\"hi\\\""))
        assertEquals("a/b", extractValue("a\\/b"))
        assertEquals("a\\b", extractValue("a\\\\b"))
    }

    @Test
    fun escapedBackslashDoesNotSwallowTheFollowingLetter() {
        // The old chain turned this into a real newline: the "\n" rule
        // matched the SECOND backslash together with the 'n'.
        assertEquals("\\n", extractValue("\\\\n"))
        assertEquals("\\t", extractValue("\\\\t"))
    }

    @Test
    fun consecutiveEscapesAreEachConsumedOnce() {
        // Four backslashes in the JSON text are two escaped backslashes, so
        // the value is two backslashes - not one. findUnescapedEnd skips two
        // characters per escape, so it lands on the real closing quote.
        assertEquals("\\\\", extractValue("\\\\\\\\"))
    }

    @Test
    fun unknownEscapeYieldsTheEscapedCharacter() {
        assertEquals("aq", extractValue("a\\q"))
    }

    @Test
    fun valueStopsAtTheClosingQuote() {
        assertEquals("first", extractValue("first\",\"other\":\"second"))
    }

    @Test
    fun unterminatedValueRunsToTheEndOfTheDocument() {
        assertEquals(
            "/var/minis/att",
            PartialJsonToolArgs.extract("path", "{\"path\": \"/var/minis/att"),
        )
    }

    @Test
    fun missingKeyReturnsNull() {
        assertNull(PartialJsonToolArgs.extract("path", "{\"other\": \"x\"}"))
    }

    @Test
    fun tightFormIsAccepted() {
        assertEquals("/x", PartialJsonToolArgs.extract("path", "{\"path\":\"/x\"}"))
    }
}