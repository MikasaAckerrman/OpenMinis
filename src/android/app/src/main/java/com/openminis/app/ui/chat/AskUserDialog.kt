package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.sandbox.AskUserGate

/**
 * [T-ask-user] The one-tap question sheet. Options render as full-width
 * buttons (thumb-reachable), optional description under each label.
 * Dismiss = skip (honest signal to the agent, never a fake "yes").
 *
 * [T-ask-user-session-scope] User report (2026-10-07): the question popped
 * in whatever session was OPEN — "откуда это окно взялось?" The request
 * carries its sessionId; the sheet now renders ONLY in the session that
 * owns it. Discovery: the waiting session keeps its streaming/active
 * marker in the session list (the turn is suspended on this gate), so the
 * user finds it by visiting the highlighted session.
 *
 * [T-ask-user-no-freetext-ui] User verdict: the embedded free-text field
 * ("Другой ответ" + its submit button) is garbage in the cells — the
 * composer is THE typing surface, a dialog must be one-tap-only. If no
 * option fits, "Пропустить" tells the agent to continue with its best
 * judgment (the gate's answerText API stays for tests/future surfaces).
 */
@Composable
fun AskUserDialog(sessionId: String) {
    val request by AskUserGate.pending.collectAsState()
    val pending = request ?: return
    if (pending.sessionId != sessionId) return


    AlertDialog(
        onDismissRequest = { AskUserGate.skip() },
        title = { Text("Вопрос от агента") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(pending.question, style = MaterialTheme.typography.bodyMedium)
                if (pending.options.isNotEmpty()) Spacer(Modifier.height(12.dp))
                pending.options.forEach { option ->
                    OutlinedButton(
                        onClick = { AskUserGate.answerOption(option.label) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            Text(option.label, style = MaterialTheme.typography.titleSmall)
                            if (option.description.isNotBlank()) {
                                Text(
                                    option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { AskUserGate.skip() }) {
                Text("Пропустить")
            }
        },
    )
}
