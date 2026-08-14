package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openminis.app.sandbox.DestructiveCommandGate

/**
 * Confirmation sheet for a destructive shell command.
 *
 * WHAT IT MUST SHOW, AND WHY EXACTLY THIS. The incident that caused this
 * feature was a glob: the agent wrote `rm -rf om*` intending one directory.
 * A dialog saying "delete files?" would have been approved without thought.
 * So the command is shown **verbatim**, in a monospace font, with the
 * triggering fragment called out separately — the whole point is that the user
 * can see the `*` and think "wait, what else does that match".
 *
 * DEFAULT IS DENY. The dismissive action (tap outside, back press) denies.
 * Approving requires the explicit button.
 */
@Composable
fun DestructiveCommandDialog() {
    val request by DestructiveCommandGate.pending.collectAsState()
    val pending = request ?: return

    AlertDialog(
        onDismissRequest = { DestructiveCommandGate.deny() },
        title = { Text("Подтвердите удаление") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    pending.reason,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Команда:",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    pending.command,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                if (pending.fragment.isNotBlank() &&
                    pending.fragment != pending.command
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Под удаление попадёт:",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        pending.fragment,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { DestructiveCommandGate.approve() }) {
                Text("Удалить")
            }
        },
        dismissButton = {
            TextButton(onClick = { DestructiveCommandGate.deny() }) {
                Text("Отмена")
            }
        },
    )
}
