package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The cancellation of a real HTTP call.
 *
 * The server takes the request and never answers it, so the client waits
 * for a body that never comes - the same shape as a stalled connection.
 * Cancelling the coroutine has to end the stream instead of leaving it
 * hanging, and the request must not be sent a second time: OkHttp would
 * retry a broken connection on its own, so a second request would mean the
 * cancellation never reached the call.
 */
class OpenAIProviderCancelTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        // Must not block: the client released the connection.
        server.shutdown()
    }

    @Test
    fun `cancelling the stream ends it and the request is not sent again`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val provider = OpenAIProvider(
            apiKey = "test-key",
            basePath = server.url("/v1").toString(),
        )

        val job: Job = launch {
            provider.streamMessageClamped(
                messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "hello")),
                systemPrompt = null,
                maxTokens = 64,
                temperature = null,
                imageParts = emptyList(),
                tools = emptyList(),
                thinkingLevel = ThinkingLevel.OFF,
            ).collect { }
        }

        // The request reached the server and the client waits for the body.
        withTimeout(20_000) {
            while (server.requestCount == 0) delay(20)
        }
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("the request must reach the server", recorded)
        assertTrue(
            "the chat completions path must be called",
            recorded!!.path.orEmpty().contains("chat/completions"),
        )

        job.cancel()
        // The waiting stream ends instead of hanging.
        withTimeout(20_000) { job.join() }

        // Sent once. A retry would show up as a second request.
        assertEquals(1, server.requestCount)
    }
}
