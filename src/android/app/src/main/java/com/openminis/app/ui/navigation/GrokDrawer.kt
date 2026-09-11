package com.openminis.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.agent.SoulStore
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.launch

/**
 * Grok-style navigation drawer — layout mirrors grok_ui_drawer specs
 * (grok_design_spec.md):
 * ┌─────────────────────────────────────────┐
 * │ [avatar 56] SoulName            [✕ 56]  │ header, y=58.3dp
 * ├─────────────────────────────────────────┤
 * │ Разговоры (section label)               │
 * │ ┌ session item 74.7dp ────────────────┐ │
 * │ │ title 14sp                    [⋮]   │ │
 * │ │ timestamp 12sp                      │ │
 * │ └─────────────────────────────────────┘ │
 * ├─────────────────────────────────────────┤
 * │ [ search pill ]          [⚙ 56] [＋ 56] │ bottom bar, y=842.3dp
 * └─────────────────────────────────────────┘
 */
@Composable
fun GrokDrawer(
    drawerState: DrawerState,
    chatRepository: ChatRepository,
    onSessionClick: (String) -> Unit,
    onNewChat: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<ChatSessionEntity>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    val soulMeta by SoulStore.cachedMetadata.collectAsState()
    val displayName = soulMeta.name.ifBlank { "Minis" }

    LaunchedEffect(Unit) {
        chatRepository.observeSessions().collect { list ->
            sessions = list.sortedByDescending { it.updatedAt }
        }
    }

    val filtered = if (searchQuery.isBlank()) sessions
    else sessions.filter {
        (it.title ?: "").contains(searchQuery, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 18.7.dp),
    ) {
        Spacer(Modifier.height(52.dp))

        // ── Header: avatar + Soul name + close ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = displayName.take(1).uppercase(),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(18.dp))
            Text(
                text = displayName,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { scope.launch { drawerState.close() } }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Conversations label ──
        Text(
            text = "Разговоры",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )

        // ── Chat history ──
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(filtered, key = { it.id }) { session ->
                DrawerSessionItem(
                    title = session.title?.takeIf { it.isNotBlank() } ?: "Новый чат",
                    timestamp = formatSessionTime(session.updatedAt),
                    onClick = {
                        scope.launch { drawerState.close() }
                        onSessionClick(session.id)
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Bottom bar: search + settings + new chat ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Search pill
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = "Поиск",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = {
                scope.launch { drawerState.close() }
                onSettingsClick()
            }) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(onClick = {
                scope.launch { drawerState.close() }
                onNewChat()
            }) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "New chat",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

private fun formatSessionTime(updatedAt: Long): String {
    if (updatedAt <= 0L) return ""
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = updatedAt
    val now = java.util.Calendar.getInstance()
    val sameDay = cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
        cal.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
    val fmt = if (sameDay) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    } else {
        java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault())
    }
    return fmt.format(cal.time)
}

@Composable
private fun DrawerSessionItem(
    title: String,
    timestamp: String,
    onClick: () -> Unit,
) {
    // Grok: chat items 74.7dp tall, title 14sp, timestamp 12sp muted.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (timestamp.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = timestamp,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
