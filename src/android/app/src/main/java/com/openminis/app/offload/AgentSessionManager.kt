package com.openminis.app.offload

import android.content.Context
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.tools.AgentToolPolicyStore
import com.openminis.app.tools.AgentSystemPromptStore
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
     *
     * [agentRunId] and [agentRole], when given, tag the session as a worker of
     * a multi-agent run. Tagged sessions are hidden from the main chat list —
     * an eight-node run would otherwise add eight untitled chats for one
     * request. Tagging happens BEFORE the first prompt so the session never
     * flickers into the list.
     */
    suspend fun createAndBindSession(
        context: Context,
        modelEntryId: String,
        allowedTools: List<String> = emptyList(),
        agentRunId: String? = null,
        agentRole: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val sessionId = HeadlessChatRunner.ensureSession(context, null)
        HeadlessChatRunner.applyModelOverride(context, sessionId, modelEntryId, null)
        AgentToolPolicyStore.setPolicy(sessionId, allowedTools)
        if (agentRunId != null && agentRole != null) {
            val app = context.applicationContext as com.openminis.app.MinisApp
            app.chatRepository.dao.markAsAgentWorker(sessionId, agentRunId, agentRole)
        }
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

    /** Delete a session and its messages. */
    suspend fun deleteSession(
        context: Context,
        sessionId: String,
    ) = withContext(Dispatchers.IO) {
        AgentToolPolicyStore.clearPolicy(sessionId)
        AgentSystemPromptStore.clearPrompt(sessionId)
        val app = context.applicationContext as com.openminis.app.MinisApp
        app.chatRepository.deleteSession(sessionId)
    }
}