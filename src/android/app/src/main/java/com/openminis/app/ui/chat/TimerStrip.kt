package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.offload.TurnTimerPolicy
import kotlinx.coroutines.delay

/**
 * [T-turn-timer] The minimal always-visible countdown: monospace digits plus
 * a 2dp hairline progress bar, pinned to the visual bottom of the chat
 * (reverseLayout) so it stays on screen while the agent works through its
 * budget. Deliberately foundation-only (no Material3 progress API — version
 * churn), deliberately quiet: a neutral grey while healthy, amber under 15%,
 * red when the budget is spent and the wrap-up summary is due.
 */
@Composable
fun TurnTimerStrip(
    deadlineMs: Long,
    totalMs: Long,
    modifier: Modifier = Modifier,
) {
    var nowMs by remember(deadlineMs) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadlineMs) {
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val remaining = TurnTimerPolicy.remainingMs(deadlineMs, nowMs)
    val expired = remaining <= 0
    val low = !expired && totalMs > 0 && remaining.toDouble() / totalMs <= 0.15
    val accent = when {
        expired -> Color(0xFFF26D6D)
        low -> Color(0xFFE8B45A)
        else -> Color(0xFF8A97A8)
    }
    val digits = if (expired) "ИТОГ" else TurnTimerPolicy.format(remaining)
    val fraction = if (totalMs > 0) (remaining.toDouble() / totalMs).coerceIn(0.0, 1.0) else 0.0

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "⏳ $digits",
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = accent,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(accent.copy(alpha = 0.22f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.toFloat())
                    .fillMaxHeight()
                    .background(accent),
            )
        }
    }
}
