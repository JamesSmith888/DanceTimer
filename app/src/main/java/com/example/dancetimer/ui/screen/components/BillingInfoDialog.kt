package com.example.dancetimer.ui.screen.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.dancetimer.data.model.PriceTier
import com.example.dancetimer.util.CostCalculator

/**
 * 计费说明弹窗 — 展示当前规则的计费逻辑和示例。
 */
@Composable
fun BillingInfoDialog(
    tiers: List<PriceTier>,
    ruleName: String,
    onDismiss: () -> Unit
) {
    val sorted = tiers.sortedBy { it.durationMinutes }
    val first = sorted.firstOrNull()
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("计费说明", style = typography.titleLarge)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "当前规则: $ruleName",
                    style = typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))

                if (first != null) {
                    val songDurSec = (first.durationMinutes * 60).toInt()
                    val midpointSec = songDurSec / 2

                    Text(
                        text = "每曲 ${formatMinuteLabel(first.durationMinutes)} / ${CostCalculator.formatCost(first.price)}",
                        style = typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "半曲中点计费：过了每首歌一半即收该首歌费用",
                        style = typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "⏸️ 停止缓冲",
                        style = typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "歌曲结束后 ${CostCalculator.GRACE_PERIOD_SECONDS} 秒内停止，" +
                                "费用不会跳到下一首。" +
                                "避免因走到手机旁、解锁、点击停止等操作延迟导致多收费。",
                        style = typography.bodySmall,
                        color = colors.onSurfaceVariant.copy(alpha = 0.8f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "💰 计费示例",
                        style = typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    for (i in 0..2) {
                        val midSec = i * songDurSec + midpointSec
                        val midMin = midSec / 60
                        val midSecRem = midSec % 60
                        val timeLabel = if (midSecRem > 0) "${midMin}分${midSecRem}秒" else "${midMin}分"
                        val cost = CostCalculator.calculateRaw(midSec, sorted)
                        Text(
                            text = "• $timeLabel 起: ${CostCalculator.formatCost(cost)}（已计${i + 1}曲）",
                            style = typography.bodySmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    Text(
                        text = "  …以此类推",
                        style = typography.bodySmall,
                        color = colors.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                } else {
                    Text(
                        text = "未配置价格档位",
                        style = typography.bodyMedium
                    )
                }
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
