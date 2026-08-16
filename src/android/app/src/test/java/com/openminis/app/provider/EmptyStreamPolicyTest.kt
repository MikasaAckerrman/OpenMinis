package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyStreamPolicyTest {

    @Test
    fun `clean empty completion is retried`() {
        assertTrue(
            EmptyStreamPolicy.shouldRetry(
                hasVisibleText = false,
                hasCompletedToolCall = false,
                hasMedia = false,
                finishReason = "stop",
            ),
        )
        assertTrue(
            EmptyStreamPolicy.shouldRetry(
                hasVisibleText = false,
                hasCompletedToolCall = false,
                hasMedia = false,
                finishReason = "end_turn",
            ),
        )
    }

    @Test
    fun `silent empty completion is retried`() {
        assertTrue(
            EmptyStreamPolicy.shouldRetry(
                hasVisibleText = false,
                hasCompletedToolCall = false,
                hasMedia = false,
                finishReason = null,
            ),
        )
    }

    @Test
    fun `empty response marker remains distinguishable after transient wrapping`() {
        assertTrue(
            EmptyStreamPolicy.isEmptyResponse(
                LLMError.TransientError(EmptyStreamPolicy.ERROR_DETAIL),
            ),
        )
        assertTrue(
            !EmptyStreamPolicy.isEmptyResponse(
                LLMError.TransientError("unrelated transient failure"),
            ),
        )
    }

    @Test
    fun `provider clean completion names are case normalized`() {
        assertTrue(EmptyStreamPolicy.shouldRetry(false, false, false, "STOP"))
        assertTrue(EmptyStreamPolicy.shouldRetry(false, false, false, "completed"))
        assertTrue(EmptyStreamPolicy.shouldRetry(false, false, false, "stop_sequence"))
    }

    @Test
    fun `usable assistant result is accepted`() {
        assertFalse(EmptyStreamPolicy.shouldRetry(true, false, false, "stop"))
        assertFalse(EmptyStreamPolicy.shouldRetry(false, true, false, "tool_calls"))
        assertFalse(EmptyStreamPolicy.shouldRetry(false, false, true, "stop"))
    }

    @Test
    fun `token limit completion is not retried as transient empty`() {
        assertFalse(
            EmptyStreamPolicy.shouldRetry(
                hasVisibleText = false,
                hasCompletedToolCall = false,
                hasMedia = false,
                finishReason = "length",
            ),
        )
        assertFalse(
            EmptyStreamPolicy.shouldRetry(
                hasVisibleText = false,
                hasCompletedToolCall = false,
                hasMedia = false,
                finishReason = "max_tokens",
            ),
        )
    }
}
