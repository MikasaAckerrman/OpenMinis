package com.openminis.app.offload

import android.content.Context
import android.util.Log
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ProviderFactory
import org.json.JSONObject

/**
 * Decides whether a chat message deserves the multi-agent graph at all.
 *
 * Why this exists as its own step: complexity classification used to be part of
 * the orchestrator's job, which meant paying for a full orchestrator turn on an
 * expensive model just to learn that "fix this typo" is trivial. The dispatcher
 * answers that question with one short call on a cheap model
 * (`agent.autoRouteModel`), so simple requests never touch the graph and cost
 * nothing beyond a handful of tokens.
 *
 * Fail-safe by construction: every failure path — routing disabled, no model
 * configured, network error, unparseable reply — resolves to
 * [Decision.NormalChat]. A broken dispatcher must never escalate a trivial
 * message into a seven-node graph run; the worst it may do is leave the app
 * behaving exactly as it did before auto-routing existed.
 */
object AgentDispatcher {

    private const val TAG = "AgentDispatcher"

    /** Mirrors AgentSettingsCollection — same prefs file, same keys. */
    private const val PREFS_NAME = "agent_settings_prefs"
    private const val KEY_AUTO_ROUTE = "auto_route_enabled"
    private const val KEY_AUTO_ROUTE_MODEL = "auto_route_model_entry_id"
    private const val KEY_DEFAULT_GRAPH = "agent_default_graph"

    /**
     * How much of the user's message the classifier sees. The decision only
     * needs the shape of the request, not its full body, and a long paste would
     * defeat the point of a cheap call.
     */
    private const val MAX_PROMPT_CHARS = 2000

    /** Upper bound on the classifier's own reply — it emits a tiny JSON object. */
    private const val MAX_REPLY_TOKENS = 200

    /**
     * Task complexity, as understood by the graph roles' prompts.
     *
     * L0/L1 stay in normal chat: a single model answering directly is both
     * cheaper and better for them, since a graph adds handoff overhead without
     * adding review value. L2+ is where splitting the work starts to pay.
     */
    enum class Level {
        /** Question, lookup, or one-line edit. No code review needed. */
        L0,

        /** Single-file change with an obvious shape. */
        L1,

        /** Multi-step change in one area; benefits from implement-then-review. */
        L2,

        /** Cross-cutting change; needs discovery and architecture up front. */
        L3,

        /** New subsystem or migration; full pipeline. */
        L4,
        ;

        val needsGraph: Boolean get() = this >= L2
    }

    sealed interface Decision {
        /** Answer in the current chat, exactly as before auto-routing existed. */
        data class NormalChat(val reason: String) : Decision

        /** Hand the request to [graphId]; [level] and [rationale] are for the UI. */
        data class RunGraph(
            val graphId: String,
            val level: Level,
            val rationale: String,
        ) : Decision
    }

    /**
     * Classify [userMessage] and decide where it should go.
     *
     * Never throws: callers are on the message-send path, where an exception
     * would strand the user's message.
     */
    suspend fun decide(
        context: Context,
        providerRepository: ProviderRepository,
        userMessage: String,
    ): Decision {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (!prefs.getBoolean(KEY_AUTO_ROUTE, false)) {
            return Decision.NormalChat("auto-routing disabled (agent.autoRoute)")
        }
        if (userMessage.isBlank()) {
            return Decision.NormalChat("empty message")
        }

        val entryId = prefs.getString(KEY_AUTO_ROUTE_MODEL, "").orEmpty()
        val entry = resolveRouterEntry(providerRepository, entryId)
        if (entry == null) {
            // Deliberately not falling back to the chat's own (expensive) model:
            // that would make routing cost as much as the thing it is meant to
            // avoid. Better to stay in normal chat until a router model is set.
            return Decision.NormalChat(
                "no router model configured (agent.autoRouteModel) — set a cheap model to enable routing",
            )
        }

        val level = try {
            classify(providerRepository, entry, userMessage)
        } catch (e: Exception) {
            Log.w(TAG, "classification failed, staying in normal chat: ${e.message}")
            return Decision.NormalChat("classifier error: ${e.javaClass.simpleName}")
        } ?: return Decision.NormalChat("classifier gave no usable level")

        if (!level.needsGraph) {
            return Decision.NormalChat("classified $level — simple enough for direct answer")
        }

        val graphId = prefs.getString(KEY_DEFAULT_GRAPH, DEFAULT_GRAPH_ID)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_GRAPH_ID

        return Decision.RunGraph(
            graphId = graphId,
            level = level,
            rationale = "classified $level",
        )
    }

    /**
     * The router model, or null when it is unset or no longer exists.
     *
     * An entry id can go stale — the user may delete the model or the whole
     * provider after picking it — so a missing entry is a normal state, not an
     * error worth surfacing.
     */
    private fun resolveRouterEntry(
        providerRepository: ProviderRepository,
        entryId: String,
    ): ModelEntry? {
        if (entryId.isBlank()) return null
        val entry = providerRepository.config.value.modelEntries.firstOrNull { it.id == entryId }
        if (entry == null) {
            Log.w(TAG, "router model entry '$entryId' no longer exists")
        }
        return entry
    }

    /** One short call. Returns null when the reply cannot be trusted. */
    private suspend fun classify(
        providerRepository: ProviderRepository,
        entry: ModelEntry,
        userMessage: String,
    ): Level? {
        val instance = providerRepository.instance(entry.providerInstanceId) ?: run {
            Log.w(TAG, "provider instance '${entry.providerInstanceId}' missing for router model")
            return null
        }
        val apiKey = providerRepository.loadApiKey(entry.providerInstanceId).orEmpty()
        val provider = ProviderFactory.create(instance, apiKey, entry.model, context)

        val prompt = userMessage.take(MAX_PROMPT_CHARS)

        val response = provider.sendMessage(
            messages = listOf(LLMMessage(role = "user", content = prompt)),
            systemPrompt = CLASSIFIER_SYSTEM_PROMPT,
            maxTokens = MAX_REPLY_TOKENS,
            // Classification must be repeatable: the same request should not
            // sometimes cost 7 model calls and sometimes 0.
            temperature = 0.0,
        )

        val level = parseLevel(response.content)
        Log.i(TAG, "router: ${entry.model.id} -> ${level ?: "unparseable"}")
        return level
    }

    /**
     * Pull a level out of the reply.
     *
     * Models wrap JSON in prose or fences no matter how firmly they are asked
     * not to, so this scans for the object and falls back to a bare token
     * search. Anything else returns null, which keeps the message in normal
     * chat rather than guessing.
     */
    internal fun parseLevel(raw: String?): Level? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
                val obj = JSONObject(text.substring(start, end + 1))
                val value = obj.optString("level").trim().uppercase()
                levelOrNull(value)?.let { return it }
            } catch (_: Exception) {
                // Fall through to the token scan below.
            }
        }

        // Bare "L2" / "level: L3" style replies.
        val match = Regex("\\bL([0-4])\\b").find(text.uppercase())
        return match?.let { levelOrNull("L" + it.groupValues[1]) }
    }

    private fun levelOrNull(value: String): Level? =
        Level.entries.firstOrNull { it.name == value }

    /** Graph used when [KEY_DEFAULT_GRAPH] is unset. */
    internal const val DEFAULT_GRAPH_ID = "builtin_light"

    private val CLASSIFIER_SYSTEM_PROMPT = """
        You classify how much work a software request needs. You do not answer it.

        Reply with exactly one JSON object and nothing else:
        {"level": "L0|L1|L2|L3|L4", "why": "<= 12 words"}

        Levels:
        L0 — question, explanation, lookup, or a one-line edit. No review needed.
        L1 — single-file change with an obvious shape.
        L2 — multi-step change in one area; worth implementing then reviewing.
        L3 — cross-cutting change touching several areas; needs codebase discovery
             and an architecture decision first.
        L4 — new subsystem, migration, or anything needing the full pipeline.

        Bias low. Escalating a simple request wastes far more than under-rating a
        complex one, because the user can always ask for a deeper pass. When torn
        between two levels, choose the lower.
    """.trimIndent()
}
