package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.engine.ToolCategories
import com.openminis.app.offload.AgentFileParser
import com.openminis.app.offload.AgentFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [B16] The Custom Agents screen: list / create / edit / delete the
 * user-defined subagent files that live under
 * `filesDir/minis-global/agents/<name>.md` (sandbox `/var/minis/agents/`).
 *
 * The screen does NOT own any registry — [AgentFileStore] re-scans the
 * directory on every spawn (no cache by design), so a saved file is live
 * immediately: the agent appears in `list_agents` and is spawnable as
 * `custom:<name>` without a restart.
 *
 * The tool picker is rendered from the engine's category table
 * ([ToolCategories.CATEGORIES]) — the same structural source the file
 * parser validates against, so anything pickable here parses back.
 *
 * Stage-1 strings are hardcoded English (localization pass is a separate
 * follow-up; consistent with the newer settings screens that ship ahead of
 * their translation round).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomAgentsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var agents by remember { mutableStateOf<List<AgentFileParser.AgentFile>>(emptyList()) }
    var draft by remember { mutableStateOf<AgentDraft?>(null) }
    var hudError by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            agents = withContext(Dispatchers.IO) {
                runCatching { AgentFileStore.list(context) }.getOrDefault(emptyList())
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { reload() }

    val current = draft
    if (current == null) {
        AgentListScreen(
            agents = agents,
            onBack = onBack,
            onCreate = { draft = AgentDraft.empty() },
            onEdit = { file ->
                draft = AgentDraft.fromFile(file)
            },
            onDelete = { agent ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            // `== true` normalizes the nullable Boolean from
                            // the ?. chain into a plain Boolean.
                            AgentFileStore.fileFor(context, agent.fileName)?.delete() == true
                        }.getOrDefault(false)
                    }
                    if (!ok) hudError = "Could not delete ${agent.fileName}"
                    reload()
                }
            },
            error = hudError,
            onDismissError = { hudError = null },
        )
    } else {
        AgentEditorScreen(
            draft = current,
            isNew = current.originalFileName == null,
            // Write failures (rare: app-private dir) surface INSIDE the
            // editor — closing it on a failed save would drop the user's
            // edits without a trace.
            ioError = hudError,
            onBack = { draft = null },
            onSave = { saved ->
                scope.launch {
                    val error = withContext(Dispatchers.IO) {
                        runCatching { saved.writeTo(context) }.exceptionOrNull()?.message
                    }
                    if (error != null) {
                        hudError = error
                    } else {
                        draft = null
                    }
                    reload()
                }
            },
        )
    }
}

// ─── List ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentListScreen(
    agents: List<AgentFileParser.AgentFile>,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (AgentFileParser.AgentFile) -> Unit,
    onDelete: (AgentFileParser.AgentFile) -> Unit,
    error: String?,
    onDismissError: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Custom agents", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New agent") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "Markdown agent files under ${AgentFileStore.SANDBOX_DIR}/ — " +
                    "the same files the spawner reads. Save = live, no restart.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (error != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissError) { Text("Dismiss") }
                }
            }
            if (agents.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("No custom agents yet", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Create one here, or drop a .md file into " +
                            "${AgentFileStore.SANDBOX_DIR} (the agent can do it " +
                            "via file_write).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn {
                    items(agents, key = { it.fileName }) { agent ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { onEdit(agent) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(agent.name, fontWeight = FontWeight.SemiBold)
                            if (agent.description.isNotBlank()) {
                                Text(
                                    agent.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val modelHint = agent.modelEntryId
                                ?: agent.modelRole?.let { "role: $it" }
                                ?: "default model"
                            val toolHint = agent.tools?.let { "${it.size} tool(s)" }
                                ?: "default tools"
                            Text(
                                "$modelHint · $toolHint · maxTurns ${agent.maxTurns}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onDelete(agent) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ─── Editor ──────────────────────────────────────────────────────────────────

/** Valid spawn names: what the parser accepts minus path-hostile characters. */
private val NAME_REGEX = Regex("[A-Za-z0-9_-]+")

private val MODEL_ROLE_OPTIONS = listOf("default") + listOf(
    "planner", "analyst", "architect", "coder", "reviewer", "tester",
)

/**
 * The editable projection of an agent file. [tools] == null means "no
 * `tools:` line" — the runner default for the role (the parser maps an
 * empty selection to null the same way).
 */
data class AgentDraft(
    val name: String,
    val description: String,
    val modelRole: String?,      // null = default (no modelRole line)
    val modelEntryId: String?,   // null = no explicit pin
    val maxTurnsText: String,
    val restrictTools: Boolean,
    val tools: Set<String>,
    val instructions: String,
    /** Non-null when editing an existing file (its current name). */
    val originalFileName: String? = null,
) {
    val isNew: Boolean get() = originalFileName == null

    companion object {
        fun empty() = AgentDraft(
            name = "", description = "", modelRole = null, modelEntryId = null,
            maxTurnsText = AgentFileParser.AgentFile.DEFAULT_MAX_TURNS.toString(),
            restrictTools = false, tools = emptySet(), instructions = "",
        )

        fun fromFile(agent: AgentFileParser.AgentFile) = AgentDraft(
            name = agent.name,
            description = agent.description,
            modelRole = agent.modelRole,
            modelEntryId = agent.modelEntryId,
            maxTurnsText = agent.maxTurns.toString(),
            // A file WITHOUT a tools line opens with the restriction off.
            restrictTools = agent.tools != null,
            tools = agent.tools.orEmpty().toSet(),
            instructions = agent.instructions,
            originalFileName = agent.fileName,
        )
    }

    /** Null = valid; otherwise the message to show at save time. */
    fun validate(): String? {
        if (!NAME_REGEX.matches(name.trim())) {
            return "Name must be letters/digits/-/_ (no colon, no path chars)"
        }
        if (instructions.isBlank()) return "Instructions (the body) must not be empty"
        val turns = maxTurnsText.toIntOrNull()
        if (turns != null && turns !in 1..64) return "maxTurns must be 1..64"
        return null
    }

    /** Serialize to the exact frontmatter format [AgentFileParser] reads. */
    fun toMarkdown(): String {
        val fm = StringBuilder()
        fm.append("name: ").append(name.trim()).append('\n')
        if (description.isNotBlank()) fm.append("description: ").append(description.trim()).append('\n')
        modelRole?.let { fm.append("modelRole: ").append(it).append('\n') }
        modelEntryId?.takeIf { it.isNotBlank() }?.let { fm.append("model: ").append(it.trim()).append('\n') }
        if (restrictTools && tools.isNotEmpty()) {
            fm.append("tools: ").append(tools.sorted().joinToString(", ")).append('\n')
        }
        maxTurnsText.toIntOrNull()?.let { fm.append("maxTurns: ").append(it).append('\n') }
        return "---\n$fm---\n\n${instructions.trim()}\n"
    }

    /**
     * Write the file (and remove the old one on rename). App-private
     * filesDir + regex-validated name — no traversal by construction.
     */
    fun writeTo(context: android.content.Context) {
        val dir = File(context.applicationContext.filesDir, "minis-global/agents")
        dir.mkdirs()
        val oldName = originalFileName
        val newName = "${name.trim()}.md"
        val target = File(dir, newName)
        if (oldName != null && oldName != newName) {
            File(dir, oldName).delete()
        }
        target.writeText(toMarkdown())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentEditorScreen(
    draft: AgentDraft,
    isNew: Boolean,
    ioError: String?,
    onBack: () -> Unit,
    onSave: (AgentDraft) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(draft.name) }
    var description by rememberSaveable { mutableStateOf(draft.description) }
    var modelRole by rememberSaveable { mutableStateOf(draft.modelRole) }
    var modelEntryId by rememberSaveable { mutableStateOf(draft.modelEntryId.orEmpty()) }
    var maxTurnsText by rememberSaveable { mutableStateOf(draft.maxTurnsText) }
    var restrictTools by rememberSaveable { mutableStateOf(draft.restrictTools) }
    val tools = remember { mutableStateOf(draft.tools.toSet()) }
    var instructions by rememberSaveable { mutableStateOf(draft.instructions) }
    var saveError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isNew) "New agent" else "Edit agent",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val candidate = draft.copy(
                            name = name.trim(),
                            description = description.trim(),
                            modelRole = modelRole,
                            modelEntryId = modelEntryId.trim().takeIf { it.isNotEmpty() },
                            maxTurnsText = maxTurnsText.trim(),
                            restrictTools = restrictTools,
                            tools = tools.value,
                            instructions = instructions,
                            originalFileName = draft.originalFileName,
                        )
                        val problem = candidate.validate()
                        if (problem != null) {
                            saveError = problem
                        } else {
                            onSave(candidate)
                        }
                    }) {
                        Icon(Icons.Filled.Save, contentDescription = "Save")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (saveError != null) {
                Text(
                    saveError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            if (ioError != null) {
                Text(
                    ioError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (spawn id, custom:<name>)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description (one line for the catalog)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                "Model role",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MODEL_ROLE_OPTIONS.forEach { role ->
                    val selected = if (role == "default") modelRole == null else modelRole == role
                    FilterChip(
                        selected = selected,
                        onClick = {
                            modelRole = if (role == "default") null else role
                        },
                        label = { Text(role) },
                    )
                }
            }
            OutlinedTextField(
                value = modelEntryId,
                onValueChange = { modelEntryId = it },
                label = { Text("Model entry id (optional — beats role)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = maxTurnsText,
                onValueChange = { maxTurnsText = it },
                label = { Text("maxTurns (1..64, default 8)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Restrict tools", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Off = the runner default for the role (no tools: line)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = restrictTools, onCheckedChange = { restrictTools = it })
            }
            if (restrictTools) {
                ToolCategoryPicker(
                    selected = tools.value,
                    onToggle = { toolName ->
                        tools.value = tools.value.toMutableSet().apply {
                            if (!add(toolName)) remove(toolName)
                        }
                    },
                )
            }
            OutlinedTextField(
                value = instructions,
                onValueChange = { instructions = it },
                label = { Text("Instructions (file body, injected verbatim)") },
                minLines = 6,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** One expandable section per category; collapsed shows the selection count. */
@Composable
private fun ToolCategoryPicker(
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    val expanded = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    Column(Modifier.padding(top = 4.dp)) {
        ToolCategories.CATEGORIES.forEach { category ->
            val open = expanded[category.key] ?: false
            val picked = category.tools.count { it in selected }
            Row(
                Modifier.fillMaxWidth()
                    .clickable { expanded[category.key] = !open }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${category.title}  ($picked/${category.tools.size})",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }
            if (open) {
                category.tools.forEach { toolName ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onToggle(toolName) }
                            .padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = toolName in selected, onCheckedChange = { onToggle(toolName) })
                        Text(toolName, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
