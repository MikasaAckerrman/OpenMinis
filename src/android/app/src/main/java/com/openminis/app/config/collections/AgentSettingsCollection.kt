package com.openminis.app.config.collections

import com.openminis.app.config.ConfigCollection
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.config.fields.PrefsBoolField
import com.openminis.app.data.repository.ProviderRepository
import android.content.Context
import android.content.SharedPreferences

/**
 * Agent Settings — auto-routing and default graph configuration.
 * Path: `agent.autoRoute`, `agent.defaultGraph`, `agent.autoRouteModel`
 */
class AgentSettingsCollection(
    private val context: Context,
    private val providerRepo: ProviderRepository,
) : ConfigCollection {

    override val basePath: String get() = "agent"
    override val displayName: String get() = "Agent Settings"
    override val description: String get() = "Auto-routing and default graph settings for multi-agent system."
    override val addable: Boolean get() = false
    override val removable: Boolean get() = false
    override val risk: ConfigRisk get() = ConfigRisk.NORMAL

    override fun childIds(): List<String> = listOf(
        "autoRoute", "defaultGraph", "autoRouteModel", "defaultModelEntry",
    )

    override fun fields(forId: String): List<ConfigField> {
        return when (forId) {
            "autoRoute" -> listOf(autoRouteField())
            "defaultGraph" -> listOf(defaultGraphField())
            "autoRouteModel" -> listOf(autoRouteModelField())
            "defaultModelEntry" -> listOf(defaultModelEntryField())
            else -> emptyList()
        }
    }

    override fun add(payload: ConfigValue): String {
        throw ConfigError.InvalidValue("Cannot add agent settings — use minis-config set")
    }

    override fun remove(id: String) {
        throw ConfigError.InvalidValue("Cannot remove agent settings")
    }

    override val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun autoRouteField(): ConfigField =
        PrefsBoolField(
            path = "agent.autoRoute",
            displayName = "Auto-route to multi-agent graph",
            description = "When enabled, the router classifies task complexity and auto-launches the default graph for L2+ tasks. L0/L1 tasks stay in normal chat.",
            prefs = prefs,
            key = KEY_AUTO_ROUTE,
            defaultValue = false,
            risk = ConfigRisk.NORMAL,
        )

    private fun defaultGraphField(): ConfigField =
        ClosureField(
            path = "agent.defaultGraph",
            displayName = "Default graph ID",
            description = "Graph to auto-launch for complex tasks (L2+). Must match a configured graph ID from the `graphs` collection. Leave empty to let the router pick by complexity: builtin_light for L2, builtin_full for L3+.",
            valueSchema = ConfigSchema.Str(maxLength = 200),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            // Empty, not "coding_v4": no graph by that id ships with the app, so
            // reporting it as the default described a setting that could never
            // run. Empty means "let the level decide" — see
            // AgentDispatcher.defaultGraphFor.
            reader = { ConfigValue.Str(prefs.getString(KEY_DEFAULT_GRAPH, "") ?: "") },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value?.trim() ?: ""
                // Validate against the DB (synchronous wrapper) rather than the
                // in-memory cache, which is only populated after a save/load in
                // this process.
                if (s.isNotEmpty() && providerRepo.loadAgentGraphSync(s) == null) {
                    throw ConfigError.InvalidValue("Graph not found: $s")
                }
                prefs.edit().putString(KEY_DEFAULT_GRAPH, s).apply()
            },
        )

    private fun autoRouteModelField(): ConfigField =
        ClosureField(
            path = "agent.autoRouteModel",
            displayName = "Router model entry ID",
            description = "Model entry used for the lightweight task classification (L0-L4). Should be a fast/cheap model.",
            valueSchema = ConfigSchema.Str(maxLength = 200),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            reader = { ConfigValue.Str(prefs.getString(KEY_AUTO_ROUTE_MODEL, "") ?: "") },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value?.trim() ?: ""
                if (s.isNotEmpty()) {
                    // Validate entry exists - use synchronous config access
                    val entry = providerRepo.config.value.modelEntries.find { it.id == s }
                    if (entry == null) {
                        throw ConfigError.InvalidValue("Model entry not found: $s")
                    }
                }
                prefs.edit().putString(KEY_AUTO_ROUTE_MODEL, s).apply()
            },
        )

    /**
     * The one setting needed to try the multi-agent graph: every role uses this
     * model unless it has its own key under `agent.keys.<role>`.
     *
     * Without it a first run needs six provider instances and six env vars
     * before anything happens, which is a lot of setup to discover the feature
     * does not work for you. Split roles onto separate keys once it does.
     */
    private fun defaultModelEntryField(): ConfigField =
        ClosureField(
            path = "agent.defaultModelEntry",
            displayName = "Default model for all agent roles",
            description = "Model entry every agent role falls back to when it has no per-role key " +
                "in agent.keys. Empty = use whatever the agent loop already exposes. " +
                "Discover ids with `minis-config get models`.",
            valueSchema = ConfigSchema.Str(maxLength = 200),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            reader = { ConfigValue.Str(providerRepo.agentDefaultModelEntryId ?: "") },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value?.trim() ?: ""
                if (s.isNotEmpty()) {
                    val entry = providerRepo.config.value.modelEntries.find { it.id == s }
                        ?: throw ConfigError.InvalidValue(
                            "Model entry not found: $s. Run `minis-config get models` for valid ids."
                        )
                    if (entry.isHidden) {
                        throw ConfigError.InvalidValue("Model entry $s is hidden — pick a visible one")
                    }
                }
                providerRepo.agentDefaultModelEntryId = s.ifBlank { null }
            },
        )

    companion object {
        private const val PREFS_NAME = "agent_settings_prefs"
        private const val KEY_AUTO_ROUTE = "auto_route_enabled"
        private const val KEY_DEFAULT_GRAPH = "default_graph_id"
        private const val KEY_AUTO_ROUTE_MODEL = "auto_route_model_entry_id"
    }
}