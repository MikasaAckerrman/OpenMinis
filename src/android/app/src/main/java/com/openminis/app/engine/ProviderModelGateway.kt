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
import com.openminis.app.data.model.ThinkingLevel
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
 * serialized once — the engine's preflight re-parses; ToolInputDelta
 * fragments map through since M9, the progressive-args surface the
 * production UI renders). Usage→Usage, Finished→Done. ThinkingDelta→
 * ReasoningDelta and ReasoningContent→ReasoningDelta carry the
 * thinking stream since M9/M10 — the loop accumulates them into the
 * round's reasoningContent (DeepSeek history invariant).
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
    /**
     * [T-m12-image-parts] The turn's user image attachments — turn-scoped
     * input exactly like [systemPrompt], so they ride the same constructor
     * seam. The engine message history is text-shaped; attachments enter
     * at the wire call where the production path passes them.
     */
    private val imageParts: List<LLMMessage.ImagePart> = emptyList(),
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
        imageParts = imageParts,
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
                // [T-m10-reasoning-echo] DeepSeek thinking-history contract:
                // assistant rounds carry their reasoning when thinking is
                // enabled. Same field the production loop persists.
                reasoningContent = msg.reasoningContent,
            )
        }
        EngineRole.TOOL -> {
            // Fail fast at the boundary: an empty tool_call_id would be
            // rejected by the provider with a cryptic wire-level error;
            // here the caller sees the actual contract violation.
            val callId = msg.toolCallId
                ?: throw IllegalStateException(
                    "EngineMessage(TOOL) requires toolCallId — the provider " +
                        "wire protocol answers a specific call.",
                )
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult(
                        id = callId,
                        name = msg.toolName ?: "",
                        content = msg.text,
                    ),
                ),
            )
        }
        // The gateway carries the system prompt as a constructor seam.
        // Mapping SYSTEM content to a USER message would silently poison
        // the request — a future SYSTEM emitter must surface HERE, not as
        // an invisible masquerade at the provider.
        EngineRole.SYSTEM -> throw IllegalStateException(
            "EngineMessage(SYSTEM) is not mapped: the gateway owns the " +
                "system prompt (constructor parameter). Inject context " +
                "through that seam, not through the history.",
        )
    }

    /**
     * Provider chunks → engine events. Progressive surfaces map 1:1
     * (ThinkingDelta→ReasoningDelta, ToolUseStart→ToolUseStarted,
     * ToolInputDelta→ToolInputDelta) so the production reasoning UI
     * survives a loop swap. ReasoningContent (the accumulated blob
     * echoed by DeepSeek-class models), MediaAttachment, and Started
     * still map to NULL — they are request-side/persistence concerns
     * the engine loop does not consume; their handling lives in the
     * persistence seam (M10), never as synthetic stream events.
     */
    internal fun toStreamEvent(chunk: LLMStreamChunk): StreamEvent? = when (chunk) {
        is Text -> StreamEvent.TextDelta(chunk.text)
        is ThinkingDelta -> StreamEvent.ReasoningDelta(chunk.text)
        is ToolUseStart -> StreamEvent.ToolUseStarted(chunk.id, chunk.name)
        is ToolInputDelta -> StreamEvent.ToolInputDelta(chunk.id, chunk.partial)
        is ToolCallComplete -> StreamEvent.ToolCall(
            EngineToolCall(
                id = chunk.id,
                name = chunk.name,
                argsJson = chunk.args.toString(),
            ),
        )
        is Usage -> StreamEvent.Usage(chunk.usage.inputTokens, chunk.usage.outputTokens)
        is Finished -> StreamEvent.Done
        is ReasoningContent, is MediaAttachment, is Started -> null
    }
}
