package com.openminis.app.offload

import android.content.Context
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.tools.AgentToolPolicyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-agent-graph] Thin wrapper around [HeadlessChatRunner] used by
 * [AgentGraphRunner]. Keeps the graph engine free of ViewModel plumbing:
 * create a session bound to a model entry, send a prompt, read the reply.
 *
 * Deliberately narrow — only what the graph runner needs. Message-history
 * reads go through ChatRepository directly where required.
 */
internal object AgentSessionManager {

    data class PromptResult(
        val status: String,
        val responseText: String?,
        val timedOut: Boolean,
    )

    /**
     * Create a session and bind it to [modelEntryId] in one call.
     * Returns the new session id.
     *
     * NOTE: `ensureSession` takes a MODEL id (used to seed the session row);
     * the real binding is applied by `applyModelOverride`, which takes the
     * ENTRY uuid. Passing null to ensureSession lets it pick any visible
     * entry as a placeholder — applyModelOverride immediately overwrites it.
     *
     * [allowedTools] is registered in [AgentToolPolicyStore] before the caller
     * gets a chance to send a prompt, so the very first turn already sees the
     * restricted tool schema. Pass an empty list for "no restriction".
     */
    suspend fun createAndBindSession(
        context: Context,
        modelEntryId: String,
        allowedTools: List<String> = emptyList(),
    ): String = withContext(Dispatchers.IO) {
        val sessionId = HeadlessChatRunner.ensureSession(context, null)
        HeadlessChatRunner.applyModelOverride(context, sessionId, modelEntryId, null)
        AgentToolPolicyStore.setPolicy(sessionId, allowedTools)
        sessionId
    }

    /**
     * Send [text] into [sessionId] and wait for the stream to finish.
     */
    suspend fun sendAndWait(
        context: Context,
        sessionId: String,
        text: String,
        thinkingLevel: ThinkingLevel? = null,
        timeoutMs: Long = 120_000,
    ): PromptResult {
        val result = HeadlessChatRunner.prompt(
            context = context,
            sessionId = sessionId,
            text = text,
            thinkingLevel = thinkingLevel,
            wait = true,
            timeoutMs = timeoutMs,
        )
        return PromptResult(
            status = result.status,
            responseText = result.responseText,
            timedOut = result.timedOut,
        )
    }

    /**
     * Read the last assistant reply text from [sessionId], or null.
     */
    suspend fun lastAssistantText(
        context: Context,
        sessionId: String,
    ): String? = withContext(Dispatchers.IO) {
        val app = context.applicationContext as com.openminis.app.MinisApp
        val msgs = app.chatRepository.dao.loadMessages(sessionId)
        msgs.lastOrNull { it.role == "assistant" }?.let { extractText(it.partsJson) }
    }

    /** Delete a session and its messages. */
    suspend fun deleteSession(
        context: Context,
        sessionId: String,
    ) = withContext(Dispatchers.IO) {
        AgentToolPolicyStore.clearPolicy(sessionId)
        val app = context.applicationContext as com.openminis.app.MinisApp
        app.chatRepository.deleteSession(sessionId)
    }

    /** Concatenate the `text` parts of a persisted message's parts JSON. */
    private fun extractText(partsJson: String): String {
        return try {
            val arr = org.json.JSONArray(partsJson)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val part = arr.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") {
                    sb.append(part.optString("text"))
                }
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }
}