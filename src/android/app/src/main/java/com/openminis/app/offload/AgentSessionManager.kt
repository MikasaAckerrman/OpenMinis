package com.openminis.app.offload

import android.content.Context
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.tools.AgentToolPolicyStore
import com.openminis.app.tools.AgentSystemPromptStore
import com.openminis.app.tools.AgentRuntimePolicyStore
import com.openminis.app.tools.AgentWorkspaceStore
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
        maxOutputTokens: Int = 16_384,
        workspaceHostPath: String? = null,
        agentRunId: String? = null,
        agentRole: String? = null,
        /** [T-worker-write-roots] Extra jail roots for this worker's node
         *  (repo ownership for lone sequenced workers like the CI-fixer).
         *  Merged ON TOP of the default jail — never narrower. */
        writeRoots: List<String> = emptyList(),
        /** [B16-stage2b] Server-scoped allowlist for MCP tools; null = unrestricted. */
        mcpServers: List<String>? = null,
    ): String = withContext(Dispatchers.IO) {
        val sessionId = HeadlessChatRunner.ensureSession(context, null)
        HeadlessChatRunner.applyModelOverride(context, sessionId, modelEntryId, null)
        AgentToolPolicyStore.setPolicy(sessionId, allowedTools)
        com.openminis.app.tools.AgentMcpPolicyStore.setPolicy(sessionId, mcpServers)
        AgentRuntimePolicyStore.setMaxOutputTokens(sessionId, maxOutputTokens)
        if (workspaceHostPath != null) AgentWorkspaceStore.set(sessionId, workspaceHostPath)
        if (agentRunId != null && agentRole != null) {
            val app = context.applicationContext as com.openminis.app.MinisApp
            app.chatRepository.dao.markAsAgentWorker(sessionId, agentRunId, agentRole)
            // [T-parallel-write-contract] Every marked worker session gets the
            // write jail: per-session dirs + /tmp only, REFUSE on the global
            // surface (shared/memory/skills). The spawner integrates results
            // after the batch — see AgentWritePolicyStore for the contract.
            // [T-worker-write-roots] Node-declared roots (repo ownership)
            // widen the jail; the roots-aware git gate honors them too.
            com.openminis.app.tools.AgentWritePolicyStore.setJail(
                sessionId,
                com.openminis.app.tools.AgentWritePolicyStore.DEFAULT_JAIL_ROOTS + writeRoots,
            )
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

    /**
     * Delete a session and EVERY resource it owns. [B48-worker-cleanup]
     * completed the teardown: the store clears and the DB row existed, but
     * the session's OS-level and cache-level resources leaked forever —
     * every ephemeral worker that had run one shell_execute kept its
     * libproot process, its /bin/sh child, the PersistentShell-reader
     * thread and the shells/mutexes registry entries; the cached
     * ChatViewModel stayed in HeadlessChatRunner.providers; the
     * filesDir/minis-sessions/<sid> directory tree (workspace, artifacts)
     * stayed on disk. The device incident vc162 found 1552 such leftover
     * directories draining RAM and flash.
     *
     * Order matters: the shell terminates FIRST (its process may still be
     * streaming output into a ViewModel that is invalidated next), then
     * the ViewModel cache releases (refuses only if a stream is somehow
     * still live — that caller logs the refusal), then the on-disk tree.
     */
    suspend fun deleteSession(
        context: Context,
        sessionId: String,
    ) = withContext(Dispatchers.IO) {
        AgentToolPolicyStore.clearPolicy(sessionId)
        com.openminis.app.tools.AgentMcpPolicyStore.clearPolicy(sessionId)
        AgentSystemPromptStore.clearPrompt(sessionId)
        AgentRuntimePolicyStore.clear(sessionId)
        AgentWorkspaceStore.clear(sessionId)
        com.openminis.app.tools.AgentWritePolicyStore.clear(sessionId)
        // [B48-worker-cleanup] OS-level teardown: proot process, /bin/sh
        // child, reader thread, shells/mutexes/lastInjectedKeys entries.
        com.openminis.app.sandbox.ExecutionCoordinator.sessionDidTerminate(sessionId)
        // Cache-level teardown: the headless provider and the process-wide
        // ViewModel store drop this session's instance (no-op when absent;
        // refuses and keeps the cache while a stream is somehow live).
        val released = HeadlessChatRunner.invalidateViewModel(sessionId)
        if (!released) {
            android.util.Log.w(
                "AgentSessionManager",
                "[$sessionId] ViewModel cache not released - a stream was live; shell already terminated",
            )
        }
        // Disk teardown: the session directory tree (workspace + artifacts).
        runCatching {
            java.io.File(context.filesDir, "minis-sessions/$sessionId")
                .deleteRecursively()
        }.onFailure {
            android.util.Log.w("AgentSessionManager", "[$sessionId] dir cleanup failed: ${it.message}")
        }
        val app = context.applicationContext as com.openminis.app.MinisApp
        app.chatRepository.deleteSession(sessionId)
    }
}