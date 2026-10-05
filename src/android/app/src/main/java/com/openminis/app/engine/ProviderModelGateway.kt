package com.openminis.app.engine

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMStreamChunk.ThinkingDelta
import com.openminis.app.data.model.LLMStreamChunk.ToolCallComplete
import com.openminis.app.data.model.LLMStreamChunk.ToolInputDelta
import com.openminis.app.data.model.LLMStreamChunk.ToolUseStart
import com.openminis.app.data.model.LLMStreamChunk.Usage
import com.openminis.app.data.model.LLMStreamChunk.Finished
import com.openminis.app.data.model.LLMStreamChunk.ReasoningContent
import com.openminis.app.data.model.LLMStreamChunk.MediaAttachment
import com.openminis.app.data.model.LLMStreamChunk.Started
import com.openminis.app.data.model.LLMStreamChunk.Text
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ThinkingLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapNotNull
import org.json.JSONObject

/**
 * [T-m8-gateway] The production adapter: the app's [LLMProvider] behind
 * the engine's [ModelGateway] contract (M8 slice 1 — the load-bearing
 * wiring every later loop migration streams through).
 *
 * Message mapping mirrors the production request shape EXACTLY:
 *  - USER → [LLMMessage] user with plain content;
 *  - ASSISTANT + toolCalls → assistant with [AgentContentPart.ToolUse]
 *    parts (the same shape the ViewModel loop persists and the provider
 *    layer serializes);
 *  - TOOL → a USER message carrying [AgentContentPart.ToolResult] — the
 *    app-level representation OpenAIProvider emits as separate
 *    role:"tool" wire messages and AnthropicProvider renders as
 *    tool_result blocks.
 *
 * Event mapping: Text→TextDelta, ToolCallComplete→ToolCall (args
 * serialized once — the engine's preflight re-parses; the progressive
 * ToolInputDelta deltas are swallowed: the engine consumes COMPLETE
 * calls only), Usage→Usage, Finished→Done. ThinkingDelta/
 * ReasoningContent are dropped — the engine contract has no reasoning
 * surface yet; extending it is an M9 candidate BEFORE any loop swap
 * (progressive reasoning display is a production feature to preserve).
 *
 * Stream failures (thrown from the cold flow — connection closed, TLS,
 * provider errors) become [StreamEvent.Failure] with recoverable=true:
 * transport-class by definition, matching the auto-resume policy's
 * classification of raw IOExceptions.
 */
class ProviderModelGateway(
    private val provider: LLMProvider,
    private val systemPrompt: String? = null,
    private val thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
) : ModelGateway {

    override val modelId: String get() = provider.name

    override fun stream(
        messages: List<EngineMessage>,
        tools: List<AgentToolDefinition>,
        maxTokens: Int,
    ): Flow<StreamEvent> = provider.streamMessage(
        messages = messages.map(::toLLMMessage),
        systemPrompt = systemPrompt,
        maxTokens = maxTokens,
        tools = tools,
        thinkingLevel = thinkingLevel,
    )
        .mapNotNull(::toStreamEvent)
        .catch { cause ->
            emit(
                StreamEvent.Failure(
                    message = cause.message ?: cause.javaClass.simpleName,
                    recoverable = true,
                ),
            )
        }

    /** Engine history → the app's provider message shape. */
    internal fun toLLMMessage(msg: EngineMessage): LLMMessage = when (msg.role) {
        EngineRole.USER -> LLMMessage(
            role = LLMMessage.Role.USER,
            content = msg.text,
        )
        EngineRole.ASSISTANT -> {
            val parts = msg.toolCalls.map { call ->
                AgentContentPart.ToolUse(
                    id = call.id,
                    name = call.name,
                    input = runCatching { JSONObject(call.argsJson) }
                        .getOrElse { JSONObject() },
                )
            }
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = msg.text,
                contentParts = parts,
            )
        }
        EngineRole.TOOL -> LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolResult(
                    id = msg.toolCallId ?: "",
                    name = msg.toolName ?: "",
                    content = msg.text,
                ),
            ),
        )
        // The gateway carries the system prompt as a constructor seam;
        // the engine loop never emits SYSTEM messages today.
        EngineRole.SYSTEM -> LLMMessage(
            role = LLMMessage.Role.USER,
            content = msg.text,
        )
    }

    /**
     * Provider chunks → engine events. Progressive-arg and reasoning
     * surfaces (ToolUseStart/ToolInputDelta/ThinkingDelta/
     * ReasoningContent/MediaAttachment/Started) are deliberately not in
     * the engine contract yet — they map to NULL and are dropped here,
     * never to a synthetic event. Extending the contract is an M9
     * candidate BEFORE any production loop swap.
     */
    internal fun toStreamEvent(chunk: LLMStreamChunk): StreamEvent? = when (chunk) {
        is Text -> StreamEvent.TextDelta(chunk.text)
        is ToolCallComplete -> StreamEvent.ToolCall(
            EngineToolCall(
                id = chunk.id,
                name = chunk.name,
                argsJson = chunk.args.toString(),
            ),
        )
        is Usage -> StreamEvent.Usage(chunk.usage.inputTokens, chunk.usage.outputTokens)
        is Finished -> StreamEvent.Done
        is ToolUseStart, is ToolInputDelta, is ThinkingDelta,
        is ReasoningContent, is MediaAttachment, is Started,
        -> null
    }
}
