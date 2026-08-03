package com.openminis.app.offload

import android.content.Context
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.debug.HeadlessChatRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wrapper around HeadlessChatRunner for agent graph execution.
 * Provides higher-level session management for agents.
 */
object AgentSessionManager {

    data class PromptResult(
        val status: String,
        val responseText: String?,
        val timedOut: Boolean,
        val deletedMessageCount: Int = 0,
        val retriedMessageId: String? = null,
    )

    /**
     * Create a new session bound to a specific model entry.
     */
    suspend fun createSession(
        context: Context,
        modelEntryId: String,
    ): String = withContext(Dispatchers.IO) {
        HeadlessChatRunner.ensureSession(context, modelEntryId)
    }

    /**
     * Create a session and bind it to a model entry in one call.
     */
    suspend fun createAndBindSession(
        context: Context,
        modelEntryId: String,
    ): String = withContext(Dispatchers.IO) {
        val sessionId = HeadlessChatRunner.ensureSession(context, modelEntryId)
        val modelName = HeadlessChatRunner.applyModelOverride(context, sessionId, modelEntryId, null)
        sessionId
    }

    /**
     * Send a prompt to a session and wait for completion.
     */
    suspend fun sendAndWait(
        context: Context,
        sessionId: String,
        text: String,
        thinkingLevel: com.openminis.app.data.model.ThinkingLevel? = null,
        timeoutMs: Long = 120_000,
    ): PromptResult = withContext(Dispatchers.Main) {
        val result = HeadlessChatRunner.prompt(
            context = context,
            sessionId = sessionId,
            text = text,
            thinkingLevel = thinkingLevel,
            wait = true,
            timeoutMs = timeoutMs,
        )
        PromptResult(
            status = result.status,
            responseText = result.responseText,
            timedOut = result.timedOut,
            deletedMessageCount = result.deletedMessageCount,
            retriedMessageId = result.retriedMessageId,
        )
    }

    /**
     * Send a prompt without waiting (async).
     */
    suspend fun sendAsync(
        context: Context,
        sessionId: String,
        text: String,
        thinkingLevel: com.openminis.app.data.model.ThinkingLevel? = null,
    ): PromptResult = withContext(Dispatchers.Main) {
        val result = HeadlessChatRunner.prompt(
            context = context,
            sessionId = sessionId,
            text = text,
            thinkingLevel = thinkingLevel,
            wait = false,
            timeoutMs = 0,
        )
        PromptResult(
            status = result.status,
            responseText = result.responseText,
            timedOut = result.timedOut,
        )
    }

    /**
     * Get all messages from a session.
     */
    suspend fun getMessages(
        context: Context,
        sessionId: String,
    ): List<LLMMessage> = withContext(Dispatchers.IO) {
        val app = context.applicationContext as com.openminis.app.MinisApp
        app.chatRepository.dao.loadMessages(sessionId)
            .map { it.toLLMMessage() }
    }

    /**
     * Get the last assistant message from a session.
     */
    suspend fun getLastAssistantMessage(
        context: Context,
        sessionId: String,
    ): String? = withContext(Dispatchers.IO) {
        val app = context.applicationContext as com.openminis.app.MinisApp
        val msgs = app.chatRepository.dao.loadMessages(sessionId)
        msgs.lastOrNull { it.role == "assistant" }?.let { extractText(it.partsJson) }
    }

    /**
     * Delete a session and clean up.
     */
    suspend fun deleteSession(
        context: Context,
        sessionId: String,
    ) = withContext(Dispatchers.IO) {
        val app = context.applicationContext as com.openminis.app.MinisApp
        app.chatRepository.deleteSession(sessionId)
    }

    /**
     * Extract plain text from parts JSON.
     */
    private fun extractText(partsJson: String): String {
        try {
            val arr = org.json.JSONArray(partsJson)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val part = arr.getJSONObject(i)
                if (part.getString("type") == "text") {
                    sb.append(part.getString("text"))
                }
            }
            return sb.toString()
        } catch (_: Exception) {
            return ""
        }
    }
}