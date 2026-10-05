package com.openminis.app.engine

import com.openminis.app.data.model.AgentToolDefinition
import kotlinx.coroutines.flow.Flow

/**
 * [T-model-gateway] The engine's view of an LLM provider.
 *
 * The app's provider layer (OpenAI/Anthropic/Gemini/...) adapts into this
 * surface; the engine never sees OkHttp, SSE framing, or provider-specific
 * JSON. Engine-side message types are deliberately minimal — the adapter
 * maps between them and the app's LLMMessage graph.
 */

enum class EngineRole { SYSTEM, USER, ASSISTANT, TOOL }

data class EngineToolCall(
    val id: String,
    val name: String,
    val argsJson: String,
)

data class EngineMessage(
    val role: EngineRole,
    val text: String = "",
    val toolCalls: List<EngineToolCall> = emptyList(),
    /** For role == TOOL: the call this message answers. */
    val toolCallId: String? = null,
    /**
     * For role == TOOL: the called tool's name. The provider wire protocol
     * (Anthropic tool_result blocks) requires it alongside the id; the
     * gateway adapter reads it when mapping to AgentContentPart.ToolResult.
     */
    val toolName: String? = null,
)

/** Provider stream events, provider-agnostic. */
sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ToolCall(val call: EngineToolCall) : StreamEvent
    data class Usage(val inputTokens: Int, val outputTokens: Int) : StreamEvent
    data class Failure(val message: String, val recoverable: Boolean) : StreamEvent
    object Done : StreamEvent
}

interface ModelGateway {
    val modelId: String

    /**
     * Stream one completion. The flow is cold: collection starts the
     * request, cancellation aborts it. Emits TextDelta/ToolCall deltas and
     * terminates with exactly one of Done / Failure.
     */
    fun stream(
        messages: List<EngineMessage>,
        tools: List<AgentToolDefinition>,
        maxTokens: Int,
    ): Flow<StreamEvent>
}
