package com.openminis.app.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Terminal
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Grok-style settings screen.
 *
 * Layout (from Grok APK reverse engineering, grok_ui_settings4.xml):
 * ┌──────────────────────────────────────────┐
 * │ [✕] Настройки                            │ top bar 56dp
 * ├──────────────────────────────────────────┤
 * │ ┌─ Profile card ───────────────────────┐ │ 383×121dp
 * │ │ [avatar 75dp] SkyKill               │ │
 * │ │              mtarasov819@gmail.com  │ │
 * │ └─────────────────────────────────────┘ │
 * │                                          │
 * │ ПРИЛОЖЕНИЕ (section title)               │
 * │ ┌─ row 56-84dp ────────────────────────┐ │
 * │ │ Внешний вид            Системный  > │ │
 * │ │ Terminal                            > │ │
 * │ │ Rootfs                              > │ │
 * │ └─────────────────────────────────────┘ │
 * │                                          │
 * │ МОДЕЛИ (section title)                   │
 * │ ┌─────────────────────────────────────┐ │
 * │ │ Провайдеры                          > │ │
 * │ │ Группы моделей                      > │ │
 * │ │ Agent модели                        > │ │
 * │ └─────────────────────────────────────┘ │
 * │                                          │
 * │ GROK (section title)                     │
 * │ ┌─────────────────────────────────────┐ │
 * │ │ Soul (персонализировать)             > │ │
 * │ │ MCP интеграции                       > │ │
 * │ │ Skills                              > │ │
 * │ └─────────────────────────────────────┘ │
 * │                                          │
 * │ ДАННЫЕ (section title)                   │
 * │ ┌─────────────────────────────────────┐ │
 * │ │ Env переменные                      > │ │
 * │ │ Память                              > │ │
 * │ │ Разрешения                          > │ │
 * │ │ Статистика                          > │ │
 * │ └─────────────────────────────────────┘ │
 * └──────────────────────────────────────────┘
 */
@Composable
fun GrokSettingsScreen(
    onBack: () -> Unit,
    onProvidersClick: () -> Unit = {},
    onModelGroupsClick: () -> Unit = {},
    onAgentModelsClick: () -> Unit = {},
    onStorageClick: () -> Unit = {},
    onEnvVarsClick: () -> Unit = {},
    onSkillsClick: () -> Unit = {},
    onTerminalClick: () -> Unit = {},
    onMemoryClick: () -> Unit = {},
    onMcpClick: () -> Unit = {},
    onSoulClick: () -> Unit = {},
    onPermissionsClick: () -> Unit = {},
    onUsageClick: () -> Unit = {},
    onRootfsClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ── Top bar: close button + title ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Настройки",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            // ── Profile card ──
            ProfileCard()

            Spacer(Modifier.height(28.dp))

            // ── Section: ПРИЛОЖЕНИЕ ──
            SectionHeader("Приложение")
            SectionCard {
                SettingsRow(
                    icon = Icons.Outlined.Tune,
                    title = "Внешний вид",
                    onClick = onTerminalClick, // placeholder
                )
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.Terminal,
                    title = "Терминал",
                    onClick = onTerminalClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.Storage,
                    title = "Rootfs",
                    onClick = onRootfsClick,
                )
            }

            Spacer(Modifier.height(28.dp))

            // ── Section: МОДЕЛИ ──
            SectionHeader("Модели")
            SectionCard {
                SettingsRow(
                    icon = Icons.Outlined.DataUsage,
                    title = "Провайдеры",
                    onClick = onProvidersClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.Groups,
                    title = "Группы моделей",
                    onClick = onModelGroupsClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.Person,
                    title = "Agent модели",
                    onClick = onAgentModelsClick,
                )
            }

            Spacer(Modifier.height(28.dp))

            // ── Section: GROK ──
            SectionHeader("Grok")
            SectionCard {
                SettingsRow(
                    icon = Icons.Outlined.Person,
                    title = "Soul (персонализировать)",
                    onClick = onSoulClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.Extension,
                    title = "MCP интеграции",
                    onClick = onMcpClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.Code,
                    title = "Skills",
                    onClick = onSkillsClick,
                )
            }

            Spacer(Modifier.height(28.dp))

            // ── Section: ДАННЫЕ ──
            SectionHeader("Данные")
            SectionCard {
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = "Env переменные",
                    onClick = onEnvVarsClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.Shield,
                    title = "Память",
                    onClick = onMemoryClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.Shield,
                    title = "Разрешения",
                    onClick = onPermissionsClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.DataUsage,
                    title = "Статистика",
                    onClick = onUsageClick,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ProfileCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(16.dp),
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar placeholder (75dp circle)
        Box(
            modifier = Modifier
                .size(75.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    RoundedCornerShape(50),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = "Minis",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Локальный AI агент",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(16.dp),
            ),
    ) {
        content()
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp),
            )
        }
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                modifier = Modifier.padding(start = 68.dp),
            )
        }
    }
}
