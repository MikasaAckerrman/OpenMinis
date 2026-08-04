package com.openminis.app.config.collections

import com.openminis.app.config.ConfigCollection
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.ProviderRepository

/**
 * Agent Keys — изолированное пространство для API ключей агентов.
 * Путь: `agent.keys.<role>` где role = planner | analyst | architect | coder | reviewer | tester
 * Значение: `$$ENV_VAR_NAME` (ссылка на env var из Settings → Environments).
 *
 * Пример:
 *   agent.keys.planner = $$AGENT_PLANNER_KEY
 *   agent.keys.coder = $$AGENT_CODER_KEY
 *
 * Рантайм (ProviderRepository.resolveModelEntryForRole) читает эту секцию,
 * находит ProviderInstance по env var и возвращает подходящий ModelEntry.
 */
class AgentKeysCollection(
    private val envVarRepo: EnvVarRepository,
    private val providerRepo: ProviderRepository,
) : ConfigCollection {

    override val basePath: String get() = "agent.keys"
    override val displayName: String get() = "Agent API Keys"
    override val description: String get() =
        "Per-role API key references (as \$\$ENV_VAR). Used by AgentGraphRunner to resolve model entries. " +
        "Keys: planner, analyst, architect, coder, reviewer, tester."
    override val addable: Boolean get() = false
    override val removable: Boolean get() = false
    override val risk: ConfigRisk get() = ConfigRisk.SENSITIVE

    companion object {
        val VALID_ROLES = setOf("planner", "analyst", "architect", "coder", "reviewer", "tester")
    }

    override fun childIds(): List<String> = VALID_ROLES.toList()

    override fun fields(forId: String): List<ConfigField> {
        if (forId !in VALID_ROLES) return emptyList()
        return listOf(keyField(forId))
    }

    override fun add(payload: ConfigValue): String {
        throw ConfigError.InvalidValue("Use \`minis-config set agent.keys.<role>=...\`")
    }

    override fun remove(id: String) {
        throw ConfigError.InvalidValue("Cannot remove built-in agent key slots")
    }

    override val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    private fun keyField(role: String): ConfigField =
        ClosureField(
            path = "agent.keys.$role",
            displayName = "Agent key: $role",
            description = "Env var reference (e.g. \$\$AGENT_${role.uppercase()}_KEY) for the provider instance used by $role role.",
            valueSchema = ConfigSchema.Str(maxLength = 200),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            reader = {
                val key = envVarRepo.getAgentKey(role) ?: ""
                ConfigValue.Str(key)
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value?.trim() ?: ""
                envVarRepo.setAgentKey(role, s.ifBlank { null })
            },
        )
}