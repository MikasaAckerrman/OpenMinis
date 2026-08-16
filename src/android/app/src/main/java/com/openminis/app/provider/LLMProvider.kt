package com.openminis.app.provider

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

interface LLMProvider {
    val name: String
    var model: LLMModel

    /**
     * Effective max output tokens ceiling for the given model.
     * Priority: model.maxOutputTokens > provider-level default.
     * Used as the upper bound in dynamicMaxTokens().
     */
    fun effectiveMaxOutputTokens(model: LLMModel): Int =
        model.maxOutputTokens ?: defaultMaxOutputTokens

    /** Provider-level fallback when model.maxOutputTokens is unknown. */
    val defaultMaxOutputTokens: Int get() = 16_384

    /**
     * [T-android-tool-splits-reply-fix] True when the streamed assistant text
     * is one monolithic `content` string per response (OpenAI Chat
     * Completions): text deltas carry NO positional relationship to
     * tool_calls deltas — a non-streaming materialisation is always
     * {content, tool_calls} with content first — so ALL text deltas of one
     * streamed response belong to a single text block that precedes the tool
     * blocks. Some endpoints (qwen) flush trailing content chunks after
     * tool_calls deltas purely as a chunking artifact; reconstructing those
     * chronologically fabricates an order the wire format cannot express.
     * False for formats with genuinely ordered output blocks (Responses API
     * output items, Anthropic content blocks), where arrival order IS the
     * semantic block order.
     */
    val streamTextIsMonolithic: Boolean get() = false

    /**
     * [T-android-thinking-level-arch] PUBLIC entry — this is what every caller
     * (agent loop, fallback, quick test, model-use, …) invokes. It is NOT
     * overridden by providers: the default implementation clamps the requested
     * thinking level to the current [model]'s ceiling ONCE, then delegates to
     * [sendMessageClamped]. Making the clamp structural (rather than a call each
     * impl must remember) means no code path can send an over-range level — a
     * fallback that swapped [model] mid-flight clamps to the NEW model's limit
     * automatically. Mirrors iOS AgentProvider.streamAgentMessage → …Clamped.
     */
    suspend fun sendMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double? = null,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): LLMResponse = sendMessageClamped(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools,
        clampThinkingLevel(thinkingLevel),
    )
    // [429-concurrent-sessions] NOTE on where the gate lives: streaming is
    // gated once in [streamMessage] (the single choke point all streams pass
    // through). Non-streaming is NOT gated here at the interface, on purpose —
    // OpenAI's sendMessageClamped delegates to streamMessage, so gating here
    // too would take a permit twice in one coroutine (a deadlock if
    // maxConcurrentStreams == 1). Instead the direct-socket providers
    // (Anthropic, Gemini) gate inside their own sendMessageClamped, which is
    // the actual network call for them. See gatedByDispatch + LlmDispatchGate.

    /** See [sendMessage] — the clamped, provider-implemented counterpart. */
    fun streamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double? = null,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): Flow<LLMStreamChunk> = streamMessageClamped(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools,
        clampThinkingLevel(thinkingLevel),
    ).gatedByDispatch(throttleKey)

    /**
     * Throttle bucket key for [LlmDispatchGate]. Provider RPM limits attach to
     * the endpoint host, so keying on host makes sessions that share a
     * provider pace each other while sessions on different providers stay
     * independent. Overridden by each concrete provider to its request host;
     * the default keeps a per-provider-name bucket so an un-overridden
     * provider is still throttled (never collapses onto a single global bucket
     * shared with everyone else).
     */
    val throttleKey: String get() = name

    /**
     * [T-android-thinking-level-arch] Provider implementations override THIS
     * (not [sendMessage]). The `thinkingLevel` received here has already been
     * clamped to the model's ceiling by [sendMessage] — implementations must NOT
     * re-clamp. Mirrors iOS `streamAgentMessageClamped`.
     */
    suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse

    /** See [sendMessageClamped]. */
    fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk>

    /**
     * [T-android-thinking-level-arch] Cap a requested thinking level to the
     * current [model]'s ceiling by rank. Used by the [sendMessage]/[streamMessage]
     * default entries above; the catalog ceiling is resolved off the live
     * `model`.
     */
    fun clampThinkingLevel(level: ThinkingLevel): ThinkingLevel {
        val ceiling = model.catalogMaxThinkingLevel
        return if (level.rank > ceiling.rank) ceiling else level
    }
}

/**
 * [T-android-empty-stream-retry] Reject a provider stream that ended without
 * anything the agent can use as an assistant result.
 *
 * A relay can return HTTP 200 and `finish_reason=stop` while emitting no text
 * and no completed tool call. Letting that through makes ChatViewModel break
 * normally and persist a blank assistant turn. Classify it as transient here so
 * the existing bounded retry/fallback path rolls the turn back before it is
 * written to history or the database.
 *
 * Reasoning-only chunks and incomplete tool-call fragments are deliberately not
 * success signals: they cannot be shown or sent back as a valid assistant turn.
 * `length`/`max_tokens` remains a real provider termination and is left for the
 * agent layer to surface with its existing terminal handling.
 */
fun Flow<LLMStreamChunk>.failOnSilentEmptyCompletion(providerName: String): Flow<LLMStreamChunk> = flow {
    var hasVisibleText = false
    var hasCompletedToolCall = false
    var hasMedia = false
    var finishReason: String? = null
    collect { chunk ->
        when (chunk) {
            is LLMStreamChunk.Text -> if (chunk.text.isNotBlank()) hasVisibleText = true
            is LLMStreamChunk.ToolCallComplete -> hasCompletedToolCall = true
            is LLMStreamChunk.MediaAttachment -> hasMedia = true
            is LLMStreamChunk.Finished -> {
                if (!chunk.stopReason.isNullOrBlank()) finishReason = chunk.stopReason
            }
            else -> Unit
        }
        emit(chunk)
    }
    if (EmptyStreamPolicy.shouldRetry(
            hasVisibleText = hasVisibleText,
            hasCompletedToolCall = hasCompletedToolCall,
            hasMedia = hasMedia,
            finishReason = finishReason,
        )
    ) {
        android.util.Log.w(
            "LLMProvider",
            "$providerName: stream completed without usable assistant output " +
                "(finishReason=${finishReason ?: "none"}, text=$hasVisibleText, " +
                "tool=$hasCompletedToolCall, media=$hasMedia) — treating as transient",
        )
        throw LLMError.TransientError(EmptyStreamPolicy.ERROR_DETAIL)
    }
}

/**
 * Admission control for an LLM stream: pace the request against the per-host
 * token bucket, then hold one of the scarce global stream permits for the
 * stream's lifetime. Both are enforced by [com.openminis.app.provider.LlmDispatchGate].
 *
 * Why wrap the Flow rather than gate at the call site: every provider's
 * [LLMProvider.streamMessage] returns through here, so a single wrapper
 * throttles ALL sessions (chat, agent-graph nodes, quick tests, model-use)
 * without each caller remembering to. The gating runs inside [flow] so it
 * executes lazily when the stream is actually collected — a Flow that's built
 * but never collected consumes no rate budget and no permit.
 *
 * The rate wait happens BEFORE taking a stream permit so a request parked on
 * the bucket isn't also squatting one of the few concurrency slots. The permit
 * is released automatically when collection ends — normal completion, upstream
 * error, or coroutine cancellation (a user cancelling the turn frees the slot
 * immediately).
 */
fun Flow<LLMStreamChunk>.gatedByDispatch(throttleKey: String): Flow<LLMStreamChunk> = flow {
    LlmDispatchGate.awaitRateSlot(throttleKey)
    LlmDispatchGate.withStreamPermit {
        emitAll(this@gatedByDispatch)
    }
}
