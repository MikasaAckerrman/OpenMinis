package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-output-limit-auto-extend] «выдаст ошибку что модель достигла лимита
 * выходных токенов, если продолжить будет работать дальше» → now it
 * continues BY ITSELF, bounded, resuming exactly at the cut.
 */
class OutputLimitPolicyTest {

    @Test
    fun `recognizes the normalized limit reasons`() {
        assertTrue(OutputLimitPolicy.reachedLimit("length"))
        assertTrue(OutputLimitPolicy.reachedLimit("LENGTH"))
        assertTrue(OutputLimitPolicy.reachedLimit(" max_tokens "))
        assertTrue(OutputLimitPolicy.reachedLimit("max_output_tokens"))
        assertFalse(OutputLimitPolicy.reachedLimit("stop"))
        assertFalse(OutputLimitPolicy.reachedLimit("end_turn"))
        assertFalse(OutputLimitPolicy.reachedLimit(null))
        assertFalse(OutputLimitPolicy.reachedLimit(""))
    }

    @Test
    fun `extension budget is small and odd-numbered`() {
        // 3: enough for long reasoning chains to land, small enough that a
        // model stuck in a loop can not burn tokens indefinitely.
        assertEquals(3, OutputLimitPolicy.MAX_AUTO_EXTENSIONS)
    }

    @Test
    fun `continuation prompt orders resume-at-cut and forbids re-emission`() {
        val text = OutputLimitPolicy.continuationPrompt(1)
        assertTrue(text.startsWith("⟳"))
        assertTrue(text.contains("1/3"))
        assertTrue(text.contains("РОВНО с места обрыва"))
        assertTrue(text.contains("не повторяй"))
        // Must not itself end with a completion sentinel or a question.
        assertFalse(text.trim().endsWith("?"))
    }

    @Test
    fun `continuation prompt carries its index`() {
        assertTrue(OutputLimitPolicy.continuationPrompt(2).contains("2/3"))
        assertTrue(OutputLimitPolicy.continuationPrompt(3).contains("3/3"))
    }
}
