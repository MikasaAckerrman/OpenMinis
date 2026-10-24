package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ProviderDatabase

/**
 * [T-agent-panel] The panel's entry point and the panel itself.
 *
 * Both live in one place so that they cannot disagree: the chip decides
 * whether to show itself, and the sheet reads the same state, so a task
 * that starts while the panel is open appears without anyone asking.
 *
 * The two databases are read here, once, when the view model is created.
 * The board lives in the provider database and the sessions in the app
 * database, and the panel needs both.
 */
@Composable
internal fun AgentRunsPanelHost(
    sessionId: String,
    onOpenSession: (String) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: AgentRunsViewModel = viewModel(
        key = "agentRuns-$sessionId",
        factory = remember(sessionId) {
            viewModelFactory {
                initializer {
                    AgentRunsViewModel(
                        teamId = sessionId,
                        boardDao = ProviderDatabase.getInstance(context).agentBoardDao(),
                        chatDao = AppDatabase.getInstance(context).chatDao(),
                    )
                }
            }
        },
    )
    val state by viewModel.uiState.collectAsState()
    var sheetShown by rememberSaveable { mutableStateOf(false) }

    if (state.hasSomethingToShow) {
        AgentRunsEntryChip(
            activeCount = state.activeCount,
            onClick = { sheetShown = true },
        )
    }

    if (sheetShown) {
        AgentRunsSheet(
            state = state,
            onDismiss = { sheetShown = false },
            onOpenSession = { id ->
                sheetShown = false
                onOpenSession(id)
            },
            onCancel = { id -> viewModel.cancel(id) },
        )
    }
}
