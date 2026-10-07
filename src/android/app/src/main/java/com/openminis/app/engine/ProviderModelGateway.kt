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
    internal fun toLLMMessage(msg: EngineMessage): LLMMessage = when (msg.role) {        EngineRole.USER -> LLMMessage(
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



    companion object {
    /**
     * [T-m12-engine-swap] History direction: the production agent history is
     * LLMMessage-shaped; the engine chain consumes EngineMessage. This is
     * the inverse of [toLLMMessage] — ToolUse contentParts become engine
     * toolCalls, ToolResult parts become TOOL messages, reasoning rides the
     * same field the DeepSeek contract expects on the way back out.
     */
    fun fromLLMMessage(msg: LLMMessage): List<EngineMessage> {
        val parts = msg.contentParts ?: return listOf(
            EngineMessage(
                role = when (msg.role) {
                    LLMMessage.Role.USER -> EngineRole.USER
                    LLMMessage.Role.ASSISTANT -> EngineRole.ASSISTANT
                },
                text = msg.content,
                reasoningContent = msg.reasoningContent,
            ),
        )
        val out = mutableListOf<EngineMessage>()
        val toolCalls = parts.mapNotNull { p ->
            (p as? AgentContentPart.ToolUse)?.let {
                EngineToolCall(
                    id = it.id,
                    name = it.name,
                    argsJson = it.input.toString(),
                )
            }
        }
        // [T-engine-user-text-annihilation] USER text parts were silently
        // dropped here: a user message with contentParts — which is EVERY
        // message the send path produces (send() builds [Text(trimmed), …]
        // before agentHistory.add) — converted to an EMPTY list. The engine
        // request then carried zero user messages: the model pattern-continued
        // the last assistant/tool turn instead of answering the user (the
        // 07.10 vc100 "agent doesn't see my messages" incident — diet sent
        // 116 messages, engine kept 105, the 11 missing were exactly the
        // user turns). USER text now maps to an EngineMessage(USER) before
        // any tool emissions. ImageData parts stay unconverted: the engine
        // message model is text-only — send() already plants a
        // "[attached image: path]" text placeholder, so the model keeps
        // awareness; full image fidelity remains a legacy-path feature and
        // one of the reasons the swap flag defaults OFF.
        if (msg.role == LLMMessage.Role.USER) {
            val textFromParts = parts.mapNotNull { p ->
                (p as? AgentContentPart.Text)?.text
            }.joinToString("\n").trim()
            // [T-engine-user-text-annihilation] Part-less user messages:
            // contentParts defaults to emptyList (NOT null) — a message
            // built without parts carries its text in msg.content and the
            // null-branch above never fires. Fall back so the text survives
            // in both shapes (caught by the plain-message regression test).
            val userText = textFromParts.ifEmpty { msg.content.trim() }
            if (userText.isNotEmpty()) {
                out += EngineMessage(role = EngineRole.USER, text = userText)
            }
        }
        if (toolCalls.isNotEmpty() || msg.role == LLMMessage.Role.ASSISTANT) {
            out += EngineMessage(
                role = EngineRole.ASSISTANT,
                text = msg.content,
                toolCalls = toolCalls,
                reasoningContent = msg.reasoningContent,
            )
        }
        parts.mapNotNull { p ->
            (p as? AgentContentPart.ToolResult)?.let { r ->
                EngineMessage(
                    role = EngineRole.TOOL,
                    toolCallId = r.id,
                    toolName = r.name,
                    text = r.content,
                )
            }
        }.forEach { out += it }
        return out
    }
}


}
