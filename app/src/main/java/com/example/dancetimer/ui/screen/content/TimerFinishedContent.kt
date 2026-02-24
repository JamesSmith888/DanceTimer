package com.example.dancetimer.ui.screen.content

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dancetimer.service.TimerState
import com.example.dancetimer.util.CostCalculator

/**
 * 计时结束页面内容。
 *
 * 视觉层次：状态标题 → 费用卡片（时长 + 曲数 + 费用 + 缓冲省钱） → 详情卡片 → 确认按钮
 */
@Composable
fun TimerFinishedContent(
    state: TimerState.Finished,
    onDismiss: () -> Unit
) {
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme

    val startTimeStr = CostCalculator.formatStartTime(state.startTimeMillis)
    val endTimeStr = CostCalculator.formatStartTime(state.endTimeMillis)
    val durationStr = CostCalculator.formatDurationChinese(state.durationSeconds)
    var showGraceExplainDialog by remember { mutableStateOf(false) }

    // ── 状态标题 ──
    Text(
        text = "计时结束",
        style = typography.headlineLarge,
        color = colors.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(24.dp))

    // ── 主费用卡片 ──
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth(0.88f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 28.dp)
        ) {
            Text(
                text = "计时结果",
                style = typography.labelMedium,
                color = colors.onPrimaryContainer.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 已计费曲数
            if (state.songCount > 0) {
                Text(
                    text = buildAnnotatedString {
                        append("已计")
                        pushStyle(SpanStyle(
                            fontSize = 44.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.primary
                        ))
                        append("\u2009${state.songCount}\u2009")
                        pop()
                        append("曲")
                    },
                    style = typography.headlineMedium,
                    color = colors.onPrimaryContainer.copy(alpha = 0.7f)
                )
            } else {
                Text(
                    text = "未满1曲",
                    style = typography.headlineMedium,
                    color = colors.onPrimaryContainer.copy(alpha = 0.75f)
                )
            }

            // 时长
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = durationStr,
                style = typography.headlineMedium,
                color = colors.onPrimaryContainer.copy(alpha = 0.85f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 费用
            Text(
                text = CostCalculator.formatCost(state.cost),
                style = typography.displaySmall,
                color = colors.onPrimaryContainer.copy(alpha = 0.9f)
            )

            // 缓冲省钱标签
            if (state.isGraceApplied) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.onPrimaryContainer.copy(alpha = 0.1f))
                        .clickable { showGraceExplainDialog = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🛡️ 停止缓冲已生效，已省 ${CostCalculator.formatCost(state.savedAmount)}",
                        style = typography.labelSmall,
                        color = colors.onPrimaryContainer
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    // ── 详情卡片 ──
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
        modifier = Modifier.fillMaxWidth(0.88f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DetailInfoRow(label = "开始时间", value = startTimeStr)
            DetailInfoRow(label = "结束时间", value = endTimeStr)
            DetailInfoRow(label = "时长", value = durationStr)
            DetailInfoRow(label = "使用规则", value = state.ruleName)
        }
    }

    Spacer(modifier = Modifier.height(36.dp))

    // ── 确认按钮 ──
    Button(
        onClick = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.6f)
            .height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
        shape = RoundedCornerShape(26.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
    ) {
        Text(
            text = "确认",
            style = typography.labelLarge,
            color = colors.onPrimary
        )
    }

    // ── 缓冲说明弹窗 ──
    if (showGraceExplainDialog) {
        GraceExplainDialog(
            savedAmount = state.savedAmount,
            onDismiss = { showGraceExplainDialog = false }
        )
    }
}

// ────────────────── 子组件 ──────────────────

@Composable
private fun DetailInfoRow(label: String, value: String) {
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
        Text(
            text = value,
            style = typography.bodyMedium.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            ),
            color = colors.onSurface
        )
    }
}

@Composable
private fun GraceExplainDialog(
    savedAmount: Float,
    onDismiss: () -> Unit
) {
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("⏸️ 停止缓冲", style = typography.titleLarge)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "✨ 保护机制",
                    style = typography.titleSmall,
                    color = colors.primary
                )
                Text(
                    text = "歌曲结束后 ${CostCalculator.GRACE_PERIOD_SECONDS} 秒内停止计时，费用不会跳到下一首。",
                    style = typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "💰 本次节省",
                    style = typography.titleSmall,
                    color = colors.primary
                )
                Text(
                    text = "您及时停止，已为您节省 ${CostCalculator.formatCost(savedAmount)}。",
                    style = typography.bodyMedium,
                    color = colors.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "避免因走到手机旁、解锁屏幕、点击停止等操作延迟导致多收费。",
                    style = typography.bodySmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        },
        containerColor = colors.surface,
        tonalElevation = 6.dp
    )
}
