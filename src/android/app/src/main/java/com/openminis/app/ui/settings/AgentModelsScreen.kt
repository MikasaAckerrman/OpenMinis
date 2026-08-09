package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository

/**
 * [T-agent-role-models] Settings → Agent Models.
 *
 * Why this screen exists: the multi-agent graph resolves a model per ROLE, and
 * until now the only way to influence that was `agent.defaultModelEntry` via the
 * `minis-config` CLI or a per-role API-key env var. A user who simply wants
 * "the planner on my cheap model, the coder on my strong one" had no UI at all,
 * and an unset value silently fell through to a catalog pick — which is how a
 * run ended up on a provider the user never funded.
 *
 * Contract mirrored from ProviderRepository.resolveModelEntryForRole:
 *  - "Use my current model" (null) = the model the user chats with today
 *    (last-used entry, else the default group's first member). This is the
 *    default for every role, so a fresh install needs no configuration.
 *  - An explicit pick overrides that for THAT role only.
 *  - A per-role API key (agent.keys.<role>) still wins over both — it exists to
 *    spread spend across keys, which is a stronger statement of intent.
 */
private val AGENT_ROLES = listOf(
    "planner" to R.string.agent_role_planner,
    "analyst" to R.string.agent_role_analyst,
    "architect" to R.string.agent_role_architect,
    "coder" to R.string.agent_role_coder,
    "reviewer" to R.string.agent_role_reviewer,
    "tester" to R.string.agent_role_tester,
)

@Composable
fun AgentModelsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()

    // Only entries the graph could actually use: visible, text-capable, on an
    // enabled provider. Showing a hidden or image-only entry here would let the
    // user pin something resolveModelEntryForRole then refuses to return.
    val selectable = remember(config) {
        providerRepository.allVisibleEntries().filter { it.model.isTextOutput }
    }

    // Label for the implicit default so the row states what will actually run
    // rather than a bare "Default".
    val currentModelLabel = remember(config) {
        val entry = providerRepository.lastUsedVisibleEntry()
            ?: providerRepository.defaultPrimaryGroupId
                ?.let { providerRepository.group(it) }
                ?.memberEntryIds
                ?.firstNotNullOfOrNull { mid -> selectable.firstOrNull { it.id == mid } }
        entry?.model?.displayName
    }

    // Bump to re-read the prefs-backed per-role values after a write (they are
    // not a StateFlow, so recomposition needs an explicit nudge).
    var revision by remember { mutableStateOf(0) }
    var editingRole by remember { mutableStateOf<String?>(null) }

    SettingsScaffold(
        title = stringResource(R.string.settings_agent_models),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.settings_agent_models_header),
            footer = stringResource(R.string.settings_agent_models_footer),
        ) {
            AGENT_ROLES.forEachIndexed { index, (role, labelRes) ->
                val pinnedId = remember(revision, role) {
                    providerRepository.agentRoleModelEntryId(role)
                }
                // A pinned entry that was deleted/hidden since must not read as
                // still active — fall back to the default label so the row tells
                // the truth about what will run.
                val pinned = selectable.firstOrNull { it.id == pinnedId }
                val valueText = pinned?.model?.displayName
                    ?: currentModelLabel?.let {
                        stringResource(R.string.agent_models_use_current_named, it)
                    }
                    ?: stringResource(R.string.agent_models_use_current)

                SettingsValueRow(
                    title = stringResource(labelRes),
                    value = valueText,
                    onClick = { editingRole = role },
                    showDivider = index != AGENT_ROLES.lastIndex,
                )
            }
        }

        if (selectable.isEmpty()) {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.agent_models_no_models),
                    showChevron = false,
                    showDivider = false,
                )
            }
        }
    }

    val role = editingRole
    if (role != null) {
        AgentRoleModelPickerSheet(
            roleTitle = stringResource(
                AGENT_ROLES.first { it.first == role }.second,
            ),
            currentModelLabel = currentModelLabel,
            entries = selectable,
            selectedEntryId = providerRepository.agentRoleModelEntryId(role),
            onSelect = { entryId ->
                providerRepository.setAgentRoleModelEntryId(role, entryId)
                revision++
                editingRole = null
            },
            onDismiss = { editingRole = null },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AgentRoleModelPickerSheet(
    roleTitle: String,
    currentModelLabel: String?,
    entries: List<com.openminis.app.data.model.ModelEntry>,
    selectedEntryId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
            item {
                androidx.compose.material3.Text(
                    text = roleTitle,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            item {
                SettingsChoiceRow(
                    title = currentModelLabel
                        ?.let { stringResource(R.string.agent_models_use_current_named, it) }
                        ?: stringResource(R.string.agent_models_use_current),
                    selected = selectedEntryId == null,
                    onSelect = { onSelect(null) },
                )
            }
            androidx.compose.foundation.lazy.itemsIndexed(
                entries,
                key = { _, entry -> entry.id },
            ) { index, entry ->
                SettingsChoiceRow(
                    title = entry.model.displayName,
                    selected = entry.id == selectedEntryId,
                    onSelect = { onSelect(entry.id) },
                    showDivider = index != entries.lastIndex,
                )
            }
        }
    }
}
