package com.example.dancetimer.ui.screen.content

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.dancetimer.data.model.PriceTier
import com.example.dancetimer.service.TimerState
import com.example.dancetimer.ui.screen.components.BillingInfoDialog
import com.example.dancetimer.ui.screen.components.DanceTimeline
import com.example.dancetimer.ui.screen.components.TimerControlButtons
import com.example.dancetimer.util.CostCalculator

/**
 * 计时中页面内容。
 *
 * 视觉层次（从上到下）：
 * ① 状态行（指示灯 + 开始时间）
 * ② 大号时钟
 * ③ 计费信息（曲数 + 费用 + 缓冲提示）
 * ④ 时间线
 * ⑤ 规则名 + 计费说明
 * ⑥ 自动计时提示（如适用）
 * ⑦ 控制按钮
 */
@Composable
fun TimerRunningContent(
    state: TimerState.Running,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onStopClick: () -> Unit,
    onCancelAutoClick: () -> Unit = {},
    onConfirmAutoClick: () -> Unit = {},
    onRuleClick: () -> Unit = {}
) {
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    var showGraceInfoDialog by remember { mutableStateOf(false) }

    // ── ① 状态行 ──
    StatusBar(state = state)

    Spacer(modifier = Modifier.height(32.dp))

    // ── ② 大号时钟 ──
    Text(
        text = CostCalculator.formatDuration(state.elapsedSeconds),
        style = typography.displayLarge,
        color = if (state.isPaused) colors.onBackground.copy(alpha = 0.5f) else colors.onBackground
    )

    Spacer(modifier = Modifier.height(16.dp))

    // ── ③ 计费信息 ──
    CostInfoSection(state = state)

    Spacer(modifier = Modifier.height(28.dp))

    // ── ④ 时间线 ──
    if (state.tiers.isNotEmpty()) {
        DanceTimeline(
            tiers = state.tiers.sortedBy { it.durationMinutes },
            elapsedSeconds = state.elapsedSeconds,
            isPaused = state.isPaused
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    // ── ⑤ 规则名 + 计费说明 ──
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = state.ruleName,
            style = typography.labelSmall,
            color = colors.primary.copy(alpha = 0.7f),
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onRuleClick() }
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showGraceInfoDialog = true }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "计费说明 ⓘ",
                style = typography.labelSmall,
                color = colors.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }

    if (showGraceInfoDialog) {
        BillingInfoDialog(
            tiers = state.tiers,
            ruleName = state.ruleName,
            onDismiss = { showGraceInfoDialog = false }
        )
    }

    Spacer(modifier = Modifier.height(32.dp))

    // ── ⑥ 自动计时提示 ──
    if (state.isAutoStarted) {
        AutoStartBanner(
            elapsedSeconds = state.elapsedSeconds,
            onCancelAutoClick = onCancelAutoClick,
            onConfirmAutoClick = onConfirmAutoClick
        )
        Spacer(modifier = Modifier.height(28.dp))
    }

    // ── ⑦ 控制按钮 ──
    TimerControlButtons(
        isPaused = state.isPaused,
        onPauseClick = onPauseClick,
        onResumeClick = onResumeClick,
        onStopClick = onStopClick
    )
}

// ────────────────── 子组件 ──────────────────

@Composable
private fun StatusBar(state: TimerState.Running) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 脉冲指示灯
            val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")
            val dotAlpha by infiniteTransition.animateFloat(
                initialValue = if (state.isPaused) 0.5f else 1f,
                targetValue = if (state.isPaused) 0.5f else 0.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dotAlpha"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (state.isPaused) colors.tertiary.copy(alpha = 0.5f)
                        else colors.primary.copy(alpha = dotAlpha)
                    )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    state.isAutoStarted -> "自动计时"
                    state.isPaused -> "已暂停"
                    else -> "计时中"
                },
                style = typography.titleSmall,
                color = if (state.isAutoStarted) colors.tertiary
                        else colors.onBackground.copy(alpha = 0.7f)
            )
        }

        Text(
            text = "${CostCalculator.formatStartTime(state.startTimeMillis)} 开始",
            style = typography.bodySmall,
            color = colors.onSurfaceVariant
        )
    }
}

@Composable
private fun CostInfoSection(state: TimerState.Running) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.songCount > 0) {
            Text(
                text = "已计${state.songCount}曲",
                style = typography.headlineMedium,
                color = colors.primary
            )
        } else {
            Text(
                text = "未满1曲",
                style = typography.headlineMedium,
                color = colors.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = CostCalculator.formatCost(state.cost),
            style = typography.titleLarge,
            color = if (state.songCount > 0) colors.primary.copy(alpha = 0.7f) else colors.onSurfaceVariant.copy(alpha = 0.5f)
        )
        // 停止缓冲
        if (state.isInGracePeriod) {
            Spacer(Modifier.height(4.dp))
            val remaining = CostCalculator.getGraceRemainingSeconds(state.elapsedSeconds, state.tiers)
            Text(
                text = "⏸️ 停止缓冲 ${remaining}s",
                style = typography.labelSmall,
                color = colors.primary
            )
        }
    }
}

@Composable
private fun AutoStartBanner(
    elapsedSeconds: Int,
    onCancelAutoClick: () -> Unit,
    onConfirmAutoClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    val timeText = if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.errorContainer.copy(alpha = 0.3f)
        ),
        modifier = Modifier.fillMaxWidth(0.92f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "🤖 息屏自动启动了计时（已 $timeText）",
                style = typography.titleSmall,
                color = colors.tertiary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "计满半首歌后将自动确认",
                style = typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onConfirmAutoClick,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Text(
                        text = "✓ 确认继续",
                        style = typography.labelLarge,
                        color = colors.onPrimary
                    )
                }
                OutlinedButton(
                    onClick = onCancelAutoClick,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Text(
                        text = "✗ 取消",
                        style = typography.labelLarge,
                        color = colors.error
                    )
                }
            }
        }
    }
}
