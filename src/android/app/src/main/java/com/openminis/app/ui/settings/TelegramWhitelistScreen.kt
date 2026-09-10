package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.TelegramWhitelistRepository

/**
 * Экран управления доступом к Telegram-чатам для MCP-сервера telegram.
 *
 * Два режима:
 * - Whitelist: только разрешённые чаты (Switch=ON → разрешён)
 * - Blacklist: все кроме заблокированных (Switch=ON → заблокирован)
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

    SettingsScaffold(
        title = stringResource(R.string.tg_whitelist_title),
        onBack = onBack,
    ) {
        // Режим: SegmentedButton
        SettingsSection {
            Text(
                text = stringResource(R.string.tg_whitelist_mode),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                SegmentedButton(
                    selected = config.mode == "whitelist",
                    onClick = { repository.setMode("whitelist") },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text(stringResource(R.string.tg_whitelist_mode_whitelist))
                }
                SegmentedButton(
                    selected = config.mode == "blacklist",
                    onClick = { repository.setMode("blacklist") },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text(stringResource(R.string.tg_whitelist_mode_blacklist))
                }
            }
            Text(
                text = if (config.mode == "whitelist") {
                    stringResource(R.string.tg_whitelist_whitelist_desc)
                } else {
                    stringResource(R.string.tg_whitelist_blacklist_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
            )
        }

        // Поиск
        SettingsSection {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.tg_whitelist_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }

        // Блокировка по типу (только в blacklist режиме)
        if (config.mode == "blacklist") {
            SettingsSection(header = "Блокировка по типу") {
                val privateBlocked = repository.isTypeBlocked("PRIVATE")
                val botBlocked = repository.isTypeBlocked("BOT")
                val privateCount = repository.countChatsByType("PRIVATE")
                val botCount = repository.countChatsByType("BOT")

                ListItem(
                    headlineContent = { Text("Блокировать все ЛС") },
                    supportingContent = {
                        Text(
                            if (privateBlocked && privateCount > 0)
                                "Включено · $privateCount ЛС заблокировано"
                            else if (privateBlocked)
                                "Включено"
                            else
                                "Все личные чаты доступны",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = privateBlocked,
                            onCheckedChange = { repository.toggleBlockedType("PRIVATE", it) },
                        )
                    },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Блокировать всех ботов") },
                    supportingContent = {
                        Text(
                            if (botBlocked && botCount > 0)
                                "Включено · $botCount ботов заблокировано"
                            else if (botBlocked)
                                "Включено"
                            else
                                "Все боты доступны",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = botBlocked,
                            onCheckedChange = { repository.toggleBlockedType("BOT", it) },
                        )
                    },
                )
            }
        }

        // Setup mode hint
        if (config.mode == "whitelist" && config.allowed.isEmpty()) {
            SettingsSection {
                Text(
                    text = "Режим настройки: все чаты доступны. " +
                        "Включите чаты, чтобы ограничить доступ.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // Список чатов
        val filteredChats = if (searchQuery.isBlank()) {
            chats
        } else {
            chats.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }

        if (filteredChats.isEmpty()) {
            SettingsSection {
                Text(
                    text = if (chats.isEmpty()) {
                        "Кэш чатов пуст. Попросите агента «обновить список чатов»."
                    } else {
                        "Ничего не найдено."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
            }
        } else {
            SettingsSection(
                header = "Чаты (${filteredChats.size})",
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 600.dp),
                ) {
                    items(filteredChats, key = { it.id }) { chat ->
                        val enabled = repository.isChatEnabled(chat.id)
                        ListItem(
                            headlineContent = { Text(chat.title, maxLines = 1) },
                            supportingContent = {
                                Text(
                                    "${chat.type}${chat.username?.let { " · @$it" } ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { checked ->
                                        repository.toggleChat(chat.id, checked)
                                    },
                                )
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }

        // Подсказка
        SettingsSection {
            Text(
                text = if (config.mode == "whitelist") {
                    "Switch=ON → чат РАЗРЕШЁН. Switch=OFF → чат недоступен."
                } else {
                    "Switch=ON → чат ЗАБЛОКИРОВАН. Switch=OFF → чат доступен."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                text = stringResource(R.string.tg_whitelist_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, top = 8.dp),
            )
        }
    }
}
