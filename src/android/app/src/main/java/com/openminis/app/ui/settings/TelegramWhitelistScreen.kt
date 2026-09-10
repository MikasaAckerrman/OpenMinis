package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openminis.app.data.repository.TelegramWhitelistRepository

/**
 * Экран управления доступом к Telegram-чатам для MCP-сервера telegram.
 *
 * Switch ON = чат доступен агенту (зелёный)
 * Switch OFF = чат заблокирован (красный)
 *
 * Читает кэш чатов из tg_chats_cache.json (обновляется агентом, не UI).
 * Изменения сохраняются в tg_whitelist.json мгновенно.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramWhitelistScreen(
    repository: TelegramWhitelistRepository,
    onBack: () -> Unit,
) {
    val config by repository.config.collectAsState()
    val chats by repository.chats.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    val isBlacklist = config.mode == "blacklist"
    val allowedCount = if (isBlacklist) chats.size - config.blocked.size else config.allowed.size
    val blockedCount = chats.size - allowedCount

    SettingsScaffold(
        title = "Telegram: доступ",
        onBack = onBack,
        scrollable = false,
    ) {
        // ── Сводка: сколько доступно / заблокировано ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                onClick = {},
                label = { Text("$allowedCount доступно") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                ),
            )
            AssistChip(
                onClick = {},
                label = { Text("$blockedCount заблокировано") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                ),
            )
        }

        // ── Режим ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Режим", style = MaterialTheme.typography.labelMedium)
            FilterChip(
                selected = !isBlacklist,
                onClick = { repository.setMode("whitelist") },
                label = { Text("Только выбранные") },
            )
            FilterChip(
                selected = isBlacklist,
                onClick = { repository.setMode("blacklist") },
                label = { Text("Все кроме выбранных") },
            )
        }
        Text(
            text = if (isBlacklist) {
                "Все чаты доступны. Отметь те, которые агент не должен читать."
            } else {
                "Агент видит только отмеченные чаты. Остальные — скрыты."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // ── Блокировка по типу (только в blacklist) ──
        if (isBlacklist) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val privateBlocked = repository.isTypeBlocked("PRIVATE")
                val botBlocked = repository.isTypeBlocked("BOT")
                val privateCount = repository.countChatsByType("PRIVATE")
                val botCount = repository.countChatsByType("BOT")

                FilterChip(
                    selected = privateBlocked,
                    onClick = { repository.toggleBlockedType("PRIVATE", !privateBlocked) },
                    label = { Text("ЛС" + if (privateCount > 0) " ($privateCount)" else "") },
                )
                FilterChip(
                    selected = botBlocked,
                    onClick = { repository.toggleBlockedType("BOT", !botBlocked) },
                    label = { Text("Боты" + if (botCount > 0) " ($botCount)" else "") },
                )
            }
        }

        // ── Поиск ──
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Поиск чатов…") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // ── Список чатов ──
        val filteredChats = if (searchQuery.isBlank()) {
            chats
        } else {
            chats.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }

        if (filteredChats.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (chats.isEmpty()) {
                        "Список чатов пуст.\nПопросите агента: «обновить список чатов»"
                    } else {
                        "Ничего не найдено"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Text(
                        "Чатов: ${filteredChats.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                items(filteredChats, key = { it.id }) { chat ->
                    val isEnabled = repository.isChatEnabled(chat.id)
                    // isEnabled in blacklist = "is in blocked list" = chat IS blocked
                    // isEnabled in whitelist = "is in allowed list" = chat IS allowed
                    // Switch ON = allowed (green), Switch OFF = blocked (red)
                    val switchChecked = if (isBlacklist) !isEnabled else isEnabled
                    val isBlockedByType = isBlacklist && (
                        (repository.isTypeBlocked("PRIVATE") && chat.type == "PRIVATE") ||
                        (repository.isTypeBlocked("BOT") && chat.type == "BOT")
                    )
                    val isBlocked = !switchChecked && !isBlockedByType
                    val typeLabel = when (chat.type) {
                        "CHANNEL" -> "Канал"
                        "SUPERGROUP" -> "Группа"
                        "PRIVATE" -> "ЛС"
                        "BOT" -> "Бот"
                        else -> chat.type
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isBlocked)
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.08f)
                                else Color.Transparent
                            )
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = chat.title.ifBlank { "(без названия)" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (switchChecked) FontWeight.Medium else FontWeight.Normal,
                                color = if (isBlockedByType)
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                else
                                    MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                            Text(
                                text = buildString {
                                    append(typeLabel)
                                    chat.username?.let { append(" · @$it") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isBlockedByType) {
                            Text(
                                "заблок. по типу",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        } else {
                            Switch(
                                checked = switchChecked,
                                onCheckedChange = { checked ->
                                    if (isBlacklist) {
                                        // checked=true → allowed → remove from blocked → toggleChat(false)
                                        // checked=false → blocked → add to blocked → toggleChat(true)
                                        repository.toggleChat(chat.id, !checked)
                                    } else {
                                        // checked=true → allowed → toggleChat(true)
                                        // checked=false → blocked → toggleChat(false)
                                        repository.toggleChat(chat.id, checked)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    uncheckedThumbColor = MaterialTheme.colorScheme.error,
                                    uncheckedTrackColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                ),
                            )
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
