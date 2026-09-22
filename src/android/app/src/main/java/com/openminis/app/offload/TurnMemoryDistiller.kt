package com.openminis.app.offload

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.AgentGraph
import com.openminis.app.data.model.AgentNode
import com.openminis.app.data.model.AgentRole
import com.openminis.app.data.model.GraphConfig
import java.util.UUID

/**
 * [T-proactive-memory] The write half of "the agent remembers by itself".
 *
 * Reading is covered by prompt injection (GLOBAL.md, canon, recent logs,
 * and now the relevance tier). Writing, empirically, only happened when the
 * model *chose* to call memory_write — which on a busy coding turn it
 * forgets, exactly when the turn contains the findings worth keeping. The
 * user's phrasing: "ты практически не работаешь с памятью… словно глаза
 * пришили".
 *
 * After each non-trivial main-session turn this distiller reads the turn
 * from the DB and runs a one-node ephemeral agent (the same hardened graph
 * path spawn_subagent uses: hidden session, tool allowlist = memory_write
 * only, budget, ephemeral cleanup) whose ONE job is to extract 0-3 durable
 * facts and write them. It never blocks the user's next message and never
 * appears in the chat list.
 *
 * Borrowed pattern: deepseek-harness post-task digest + Claude Code's
 * "CLAUDE.md is always loaded" spirit, inverted — not just read-always but
 * write-always.
 */
object TurnMemoryDistiller {

    /**
     * Pure gate inputs. Kept as a data class so the decision is testable
     * off-device without a DB or a model.
     */
    data class TurnDigest(
        val userChars: Int,
        val assistantChars: Int,
        val toolMentions: Int,
    )

    /**
     * A turn is worth distilling when it plausibly contains durable
     * knowledge: a substantial answer (>= 1500 chars) or real tool work
     * (>= 3 tool blocks). Trivial turns — "спасибо", one-line answers —
     * produce noise, not memory.
     */
    fun shouldDistill(digest: TurnDigest, memoryEnabled: Boolean): Boolean =
        memoryEnabled && (digest.assistantChars >= 1500 || digest.toolMentions >= 3)

    /** In-loop nudge cadence: every 10th tool call of the MAIN session. */
    const val NUDGE_INTERVAL = 10

    fun shouldNudge(toolCallCount: Int): Boolean =
        toolCallCount > 0 && toolCallCount % NUDGE_INTERVAL == 0

    /** Appended to the 10th/20th/… tool result so the model sees it mid-turn. */
    fun nudgeLine(): String =
        "[memory reminder] If this tool call produced a durable finding " +
            "(path, bug+fix, API usage, user decision, project convention) — " +
            "call memory_write NOW with 1-2 lines. Do not batch it for later; " +
            "later turns will not see this context."

    /**
     * Entry point from the turn's FINALLY. Fire-and-forget by the caller.
     * Never runs for worker sessions: their turns are executed through the
     * same sendMessage path, and a distiller distilling the distiller is an
     * infinite loop with a token bill.
     */
    suspend fun maybeDistill(context: Context, sessionId: String, memoryEnabled: Boolean) {
        if (!memoryEnabled) return
        // Worker guard 1: live node binding (set by the graph runner for the
        // whole duration of the worker's turn, cleared only after it ends).
        if (com.openminis.app.tools.AgentNodeBinding.of(sessionId) != null) return
        val app = context.applicationContext as MinisApp
        // Worker guard 2: the persistent marker — covers showcases and any
        // binding race.
        val session = app.chatRepository.dao.getSession(sessionId) ?: return
        if (session.agentRunId != null) return

        val msgs = app.chatRepository.dao.loadMessages(sessionId)
        val lastUserIdx = msgs.indexOfLast { it.role == "user" }
        if (lastUserIdx < 0) return
        val turn = msgs.drop(lastUserIdx + 1)
        if (turn.isEmpty()) return

        val userText = textOf(msgs[lastUserIdx].partsJson)
        val assistantText = turn.filter { it.role == "assistant" }
            .joinToString("\n") { textOf(it.partsJson) }
        val toolMentions = turn.filter { it.role == "assistant" }
            .sumOf { toolMentionsIn(it.partsJson) }

        val digest = TurnDigest(
            userChars = userText.length,
            assistantChars = assistantText.length,
            toolMentions = toolMentions,
        )
        if (!shouldDistill(digest, memoryEnabled)) return

        val input = buildString {
            appendLine("USER MESSAGE:")
            appendLine(userText.take(2000))
            appendLine()
            appendLine("ASSISTANT TURN (may be truncated):")
            appendLine(assistantText.take(6000))
            appendLine()
            appendLine("Tool blocks in this turn: $toolMentions")
        }

        val node = AgentNode(
            id = "distill-${UUID.randomUUID().toString().take(8)}",
            role = AgentRole.DOCUMENTATION_AGENT,
            systemPrompt = DISTILLER_CONTRACT,
            allowedTools = listOf("memory_write"),
            maxTurns = 4,
            modelRole = "analyst",
        )
        val graph = AgentGraph(
            id = "ephemeral-${node.id}",
            name = "Memory distiller",
            nodes = listOf(node),
            edges = emptyList(),
            entryNodeId = node.id,
            exitNodeIds = listOf(node.id),
            config = GraphConfig(
                maxParallelNodes = 1,
                defaultTimeoutMs = 120_000,
                defaultMaxOutputTokens = 2_048,
            ),
        )
        try {
            app.providerRepository.saveAgentGraph(graph)
            try {
                AgentGraphRunner.run(
                    context = context,
                    graphId = graph.id,
                    input = input,
                    taskId = node.id,
                    ephemeral = true,
                )
            } finally {
                app.providerRepository.deleteAgentGraph(graph.id)
            }
        } catch (e: Exception) {
            com.openminis.app.logging.AppLogger.warning(
                "TurnMemoryDistiller", "distill run failed: ${e.message}",
            )
        }
    }

    private const val DISTILLER_CONTRACT = """
        You are the memory distiller. Your ONE job: extract DURABLE facts from
        the conversation turn below and write each one with the memory_write
        tool.

        DURABLE means: a file path or repo location that mattered; a bug and
        its fix; an API/CLI/syntax usage discovered; a user preference or
        decision; a project convention; a provider/vendor status that will
        matter in later sessions.

        NOT durable (do not write): progress status, plans for tomorrow, what
        you are about to do, transient context, compliments.

        Rules:
        - One memory_write call per fact. 1-2 lines each, concise.
        - 0 facts is a VALID outcome — a turn with nothing durable says so and
          writes nothing. Never invent filler.
        - Write in the language of the fact itself.
        - You have the memory_write tool ONLY. After writing (or deciding not
          to), answer with the handoff block: FROM: DOCUMENTATION_AGENT,
          TO: ORCHESTRATOR, STATUS: COMPLETE, and DELIVERABLES listing the
          facts you wrote (or "none").
    """

    /** Extract text parts from a message's partsJson. */
    internal fun textOf(partsJson: String): String = try {
        val arr = org.json.JSONArray(partsJson)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("type") == "text") o.optString("text") else null
        }.joinToString("\n")
    } catch (_: Exception) {
        ""
    }

    /**
     * Count tool blocks in a partsJson. Schema variants ("tool_use",
     * "toolCall", "tool_result") all match the crude prefix check; this is a
     * gate heuristic, not a parser.
     */
    internal fun toolMentionsIn(partsJson: String): Int =
        TOOL_BLOCK_REGEX.findAll(partsJson).count()

    private val TOOL_BLOCK_REGEX = Regex("""\"type\"\s*:\s*\"tool""")
}
