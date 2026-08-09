package com.openminis.app.offload

import android.content.Context
import com.openminis.app.data.model.AgentRole
import com.openminis.app.data.model.Handoff
import com.openminis.app.data.model.HandoffStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-agent-graph-showcase] The one session a user actually reads during a run.
 *
 * A graph creates a session per agent role, which is what keeps their contexts
 * isolated — the test designer must never see the implementation, the reviewer
 * must not watch the code being written. But eight untitled chats appearing in
 * the list for one request is unusable.
 *
 * So: worker sessions are tagged with the run id and hidden from the main list;
 * this showcase session stays visible and receives a running commentary — which
 * agent started, what it delivered, what was skipped and why. Tapping through
 * to a worker session is still possible for the full transcript.
 *
 * The showcase is written to as an assistant message stream, so it renders like
 * any other chat rather than needing a bespoke screen.
 */
internal object AgentRunShowcase {

    /** Human labels for roles. The enum name in a chat bubble reads as shouting. */
    fun roleLabel(role: AgentRole): String = label(role)

    private fun label(role: AgentRole): String = when (role) {
        AgentRole.ORCHESTRATOR -> "Orchestrator"
        AgentRole.REQUIREMENTS_ANALYST -> "Requirements Analyst"
        AgentRole.CODEBASE_DISCOVERY -> "Codebase Discovery"
        AgentRole.SOLUTION_ARCHITECT -> "Solution Architect"
        AgentRole.INDEPENDENT_TEST_DESIGNER -> "Test Designer"
        AgentRole.SENIOR_IMPLEMENTER -> "Implementer"
        AgentRole.CODE_CORRECTNESS_REVIEWER -> "Correctness Reviewer"
        AgentRole.SECURITY_REVIEWER -> "Security Reviewer"
        AgentRole.PERFORMANCE_REVIEWER -> "Performance Reviewer"
        AgentRole.DEPENDENCY_GUARDIAN -> "Dependency Guardian"
        AgentRole.TEST_QUALITY_AUDITOR -> "Test Quality Auditor"
        AgentRole.FINAL_GATEKEEPER -> "Gatekeeper"
        AgentRole.DOCUMENTATION_AGENT -> "Documentation"
    }

    /**
     * Create the showcase session for [taskId] and seed it with the plan.
     * Returns its id, or null if creation failed — a broken showcase must not
     * take the run down with it, so every call site treats null as "carry on
     * without commentary".
     */
    suspend fun create(
        context: Context,
        taskId: String,
        graphName: String,
        input: String,
        nodeCount: Int,
        runtimeCount: Int,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext as com.openminis.app.MinisApp
            val session = app.chatRepository.createSession(
                modelId = "agent-graph",
                title = "$graphName — ${input.take(48)}",
            )
            app.chatRepository.dao.updateSource(session.id, "agent-run")
            app.chatRepository.dao.markAsAgentShowcase(session.id, taskId)

            appendUser(context, session.id, input)
            append(
                context, session.id,
                buildString {
                    appendLine("**$graphName**")
                    appendLine()
                    appendLine("$nodeCount agents configured, $runtimeCount running instances.")
                    appendLine("Run id: `${taskId.take(8)}`")
                    appendLine()
                    appendLine("Progress appears below as each agent finishes.")
                },
            )
            session.id
        } catch (e: Exception) {
            com.openminis.app.logging.AppLogger.warning(
                "AgentGraph", "showcase create failed: ${e.message}",
            )
            null
        }
    }

    /** An agent started. */
    suspend fun noteStart(
        context: Context,
        showcaseId: String?,
        role: AgentRole,
        runtimeId: String,
        replicaInfo: String?,
        model: String,
    ) {
        showcaseId ?: return
        val who = if (replicaInfo != null) "${label(role)} ($replicaInfo)" else label(role)
        append(context, showcaseId, "▶ **$who** started — model: $model")
    }

    /**
     * An agent finished. Shows what it delivered rather than the full transcript:
     * the deliverables are the part a reader needs, and pasting the whole reply
     * would make the showcase as long as all the worker sessions combined.
     */
    suspend fun noteHandoff(
        context: Context,
        showcaseId: String?,
        role: AgentRole,
        runtimeId: String,
        handoff: Handoff,
    ) {
        showcaseId ?: return
        val icon = when (handoff.status) {
            HandoffStatus.COMPLETE -> "✓"
            HandoffStatus.BLOCKED -> "⛔"
            HandoffStatus.NEEDS_CLARIFICATION -> "❓"
        }
        val text = buildString {
            appendLine("$icon **${label(role)}** — ${handoff.status}")
            if (handoff.deliverables.isNotEmpty()) {
                appendLine()
                appendLine("Delivered:")
                for (d in handoff.deliverables.take(6)) {
                    appendLine("- ${d.take(200)}")
                }
                if (handoff.deliverables.size > 6) {
                    appendLine("- _(+${handoff.deliverables.size - 6} more)_")
                }
            }
            if (handoff.risks.isNotEmpty()) {
                appendLine()
                appendLine("Open risks:")
                for (r in handoff.risks.take(3)) appendLine("- ${r.take(200)}")
            }
            if (handoff.status != HandoffStatus.COMPLETE && handoff.nextAction.isNotBlank()) {
                appendLine()
                appendLine("Needs: ${handoff.nextAction.take(300)}")
            }
        }
        append(context, showcaseId, text)
    }

    /** A branch was not taken. Silence here reads as a bug, so say why. */
    suspend fun noteSkipped(
        context: Context,
        showcaseId: String?,
        role: AgentRole,
        reason: String,
    ) {
        showcaseId ?: return
        append(context, showcaseId, "⤵ **${label(role)}** skipped — $reason")
    }

    /** An agent stepped outside its role and was stopped. */
    suspend fun noteOutOfScope(
        context: Context,
        showcaseId: String?,
        role: AgentRole,
        reason: String,
    ) {
        showcaseId ?: return
        append(
            context, showcaseId,
            "✖ **${label(role)}** stopped: stepped outside its role — $reason",
        )
    }

    /** An agent failed outright. */
    suspend fun noteFailure(
        context: Context,
        showcaseId: String?,
        role: AgentRole,
        reason: String,
    ) {
        showcaseId ?: return
        append(context, showcaseId, "✖ **${label(role)}** failed — $reason")
    }

    /** Final summary. */
    suspend fun noteFinish(
        context: Context,
        showcaseId: String?,
        status: String,
        nodeStatuses: Map<String, String>,
        artifactDir: String,
        artifactCount: Int,
        scopeViolations: Map<String, String>,
    ) {
        showcaseId ?: return
        val text = buildString {
            appendLine("---")
            appendLine("**Run finished: $status**")
            appendLine()
            for ((id, st) in nodeStatuses.entries.sortedBy { it.key }) {
                val mark = when (st) {
                    "COMPLETED" -> "✓"
                    "SKIPPED" -> "⤵"
                    "BLOCKED" -> "⛔"
                    "OUT_OF_SCOPE" -> "✖"
                    "FAILED" -> "✖"
                    else -> "·"
                }
                appendLine("$mark `$id` — $st")
            }
            if (scopeViolations.isNotEmpty()) {
                appendLine()
                appendLine("**Scope violations** (the run is reported as ESCALATED because of these):")
                for ((id, why) in scopeViolations) appendLine("- `$id`: $why")
            }
            appendLine()
            appendLine("$artifactCount artifact(s) in `$artifactDir`")
        }
        append(context, showcaseId, text)
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private suspend fun append(context: Context, sessionId: String, markdown: String) {
        withContext(Dispatchers.IO) {
            try {
                val app = context.applicationContext as com.openminis.app.MinisApp
                app.chatRepository.appendMessage(
                    sessionId = sessionId,
                    role = "assistant",
                    partsJson = textPartsJson(markdown),
                )
            } catch (e: Exception) {
                com.openminis.app.logging.AppLogger.warning(
                    "AgentGraph", "showcase append failed: ${e.message}",
                )
            }
        }
    }

    private suspend fun appendUser(context: Context, sessionId: String, text: String) {
        withContext(Dispatchers.IO) {
            try {
                val app = context.applicationContext as com.openminis.app.MinisApp
                app.chatRepository.appendMessage(
                    sessionId = sessionId,
                    role = "user",
                    partsJson = textPartsJson(text),
                )
            } catch (e: Exception) {
                com.openminis.app.logging.AppLogger.warning(
                    "AgentGraph", "showcase append(user) failed: ${e.message}",
                )
            }
        }
    }

    /**
     * Build the `parts_json` shape the chat layer stores and renders. Using
     * org.json rather than string concatenation because a deliverable can
     * contain quotes, newlines and backslashes — hand-built JSON would corrupt
     * the row on the first agent that mentions a Windows path.
     */
    private fun textPartsJson(text: String): String =
        org.json.JSONArray().put(
            org.json.JSONObject().put("type", "text").put("text", text),
        ).toString()
}
