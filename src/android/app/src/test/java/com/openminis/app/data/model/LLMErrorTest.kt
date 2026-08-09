package com.openminis.app.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LLMErrorTest {
    @Test
    fun `quota exceeded is distinct from invalid key and fallbackable`() {
        val e = LLMError.QuotaExceeded("insufficient_user_quota")
        assertTrue(e.isFallbackable)
        assertFalse(e is LLMError.InvalidApiKey)
        assertTrue(e.fallbackReason.contains("Quota"))
    }
}
