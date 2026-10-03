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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.sandbox.AskUserGate

/**
 * [T-ask-user] The one-tap question sheet. Options render as full-width
 * buttons (thumb-reachable), optional description under each label. Free
 * text answers when the options don't fit. Dismiss = skip (honest signal
 * to the agent, never a fake "yes").
 */
@Composable
fun AskUserDialog() {
    val request by AskUserGate.pending.collectAsState()
    val pending = request ?: return
    var freeText by remember(pending.id) { mutableStateOf("") }

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
                if (pending.allowFreeText) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = freeText,
                        onValueChange = { freeText = it },
                        label = { Text("Другой ответ") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 4,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { AskUserGate.answerText(freeText) },
                        enabled = freeText.isNotBlank(),
                    ) {
                        Text("Ответить текстом")
                    }
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
