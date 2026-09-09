package com.openminis.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.CompactMath
import com.openminis.app.data.CompactPhase
import com.openminis.app.data.CompactProgress
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay

/**
 * [T-compact-progress] Minimal live card for a running / failed compact,
 * mounted just above the composer. Grok-like restraint: one line of status,
 * a hairline progress bar, an elapsed clock — nothing else. On failure it
 * becomes the diagnostic surface: the SPECIFIC error per model attempt and
 * a retry button (the old UI showed a silent wait and then a generic
 * "не удалось сжать").
 *
 * Design tokens only (ChatColors) so the upcoming Minis redesign restyles
 * this card by touching the palette, not this file.
 */
@Composable
internal fun CompactProgressCard(
    progress: CompactProgress,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val failed = progress.failure != null

    // Elapsed clock: tick every second while the run is live.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(failed) {
        while (!failed) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = CompactMath.formatElapsed(
        if (failed) 0L else (nowMs - progress.startMs).coerceAtLeast(0),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ChatColors.inputBg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            // ── Header row ───────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    imageVector = if (failed) Icons.Default.Refresh else Icons.Default.CloseFullscreen,
                    contentDescription = null,
                    tint = if (failed) ChatColors.warningText else ChatColors.secondaryText,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = if (failed) "Сжатие не удалось" else "Сжатие сессии",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = ChatColors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!failed && progress.phase != CompactPhase.PREPARING) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${progress.percent}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = ChatColors.primaryText,
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    text = elapsed,
                    fontSize = 12.sp,
                    color = ChatColors.secondaryText,
                )
                if (failed) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Скрыть",
                        tint = ChatColors.secondaryText,
                        modifier = Modifier
                            .size(15.dp)
                            .clickable { onDismiss() },
                    )
                }
            }

            // ── Body: bar + note (running) or error + retry (failed) ─────
            if (failed) {
                Text(
                    text = progress.failure?.summary ?: "",
                    fontSize = 11.sp,
                    color = ChatColors.secondaryText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = "Повторить",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = ChatColors.sendButton,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onRetry() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            } else {
                val animated by animateFloatAsState(
                    targetValue = progress.percent / 100f,
                    animationSpec = tween(220),
                    label = "compactBar",
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(ChatColors.separator.copy(alpha = 0.35f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animated.coerceIn(0f, 1f))
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(ChatColors.sendButton),
                    )
                }
                val note = progress.routeNote ?: phaseLabel(progress)
                if (note != null) {
                    Text(
                        text = note,
                        fontSize = 10.sp,
                        color = ChatColors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun phaseLabel(progress: CompactProgress): String? = when (progress.phase) {
    CompactPhase.PREPARING -> "Подготовка…"
    CompactPhase.PINNING -> "Закрепляю факты в память"
    CompactPhase.SUMMARIZING ->
        if (progress.chunkCount > 1) {
            "Сжимаю часть ${progress.chunkIndex}/${progress.chunkCount}" +
                (progress.modelLabel?.let { " · $it" } ?: "")
        } else {
            "Модель пишет резюме" + (progress.modelLabel?.let { " · $it" } ?: "")
        }
    CompactPhase.MERGING -> "Объединяю части"
    CompactPhase.POLISHING -> "Чищу резюме"
    CompactPhase.WRITING -> "Записываю в историю"
    CompactPhase.DONE -> null
}
