package com.openminis.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-agent-panel] The panel of the agent board.
 *
 * Two sections: the tasks that are in flight, and the ones that ended
 * recently. The finished ones are there because the result of a task that
 * ended a minute ago is exactly what the user wants to open - the entry
 * point must not become useless the moment the last run finishes.
 *
 * The list is lazy because the finished section can hold twenty tasks and
 * each of them carries a card with rows of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentRunsSheet(
    state: AgentRunsUiState,
    onDismiss: () -> Unit,
    onOpenSession: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ChatColors.background,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp),
        ) {
            if (state.active.isEmpty() && state.finished.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.agent_panel_empty),
                        fontSize = 13.sp,
                        color = ChatColors.secondaryText,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }

            if (state.active.isNotEmpty()) {
                item(key = "active-header") {
                    SectionHeader(stringResource(R.string.agent_panel_active_header))
                }
                items(state.active, key = { "active-${it.task.id}" }) { row ->
                    TaskRow(row, onOpenSession, onCancel)
                }
            }

            if (state.finished.isNotEmpty()) {
                item(key = "finished-header") {
                    SectionHeader(stringResource(R.string.agent_panel_finished_header))
                }
                items(state.finished, key = { "finished-${it.task.id}" }) { row ->
                    TaskRow(row, onOpenSession, onCancel)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = ChatColors.tertiaryText,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun TaskRow(
    row: AgentTaskRow,
    onOpenSession: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    AgentTaskCard(
        task = row.task,
        status = row.status,
        live = row.live,
        stale = row.stale,
        resultSessionId = row.resultSessionId,
        cancellable = row.cancellable,
        onOpenSession = onOpenSession,
        onCancel = onCancel,
    )
}

/**
 * [T-agent-panel] The entry point of the panel, shown in the top bar.
 *
 * The number is the count of the tasks that are in flight, not the count of
 * everything the board ever held: the number next to the entry point is
 * what the user is supposed to act on, and a number that never goes down
 * teaches them to ignore it.
 *
 * The entry point stays while there is anything to look at - in flight OR
 * recently finished - so that the result of a task that just ended can
 * still be opened.
 */
@Composable
internal fun AgentRunsEntryChip(
    activeCount: Int,
    onClick: () -> Unit,
) {
    Text(
        text = stringResource(R.string.agent_panel_entry, activeCount),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = ChatColors.primaryText,
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clickable(onClick = onClick),
    )
}
