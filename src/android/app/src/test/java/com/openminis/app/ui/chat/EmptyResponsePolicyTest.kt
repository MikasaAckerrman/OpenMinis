package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyStreamOperatorTest {

    @Test
    fun `clean empty finish is raised as transient`() = runBlocking {
        try {
            flowOf<LLMStreamChunk>(
                LLMStreamChunk.Started,
                LLMStreamChunk.Finished("stop"),
            ).failOnSilentEmptyCompletion("test-provider").toList()
        } catch (error: LLMError.TransientError) {
            assertTrue(error.message.orEmpty().contains("empty response"))
            return@runBlocking
        }
        throw AssertionError("Expected TransientError")
    }

    @Test
    fun `reasoning only clean finish is raised as transient`() = runBlocking {
        try {
            flowOf<LLMStreamChunk>(
                LLMStreamChunk.Started,
                LLMStreamChunk.ThinkingDelta("internal reasoning"),
                LLMStreamChunk.Finished("end_turn"),
            ).failOnSilentEmptyCompletion("test-provider").toList()
        } catch (_: LLMError.TransientError) {
            return@runBlocking
        }
        throw AssertionError("Expected TransientError")
    }

    @Test
    fun `visible text passes through unchanged`() = runBlocking {
        val input = listOf<LLMStreamChunk>(
            LLMStreamChunk.Started,
            LLMStreamChunk.Text("answer"),
            LLMStreamChunk.Finished("stop"),
        )
        assertEquals(
            input,
            flowOf(*input.toTypedArray())
                .failOnSilentEmptyCompletion("test-provider")
                .toList(),
        )
    }
}
