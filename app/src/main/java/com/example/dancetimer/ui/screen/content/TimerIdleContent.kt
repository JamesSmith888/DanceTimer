package com.example.dancetimer.ui.screen.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dancetimer.data.model.PricingRuleWithTiers
import com.example.dancetimer.data.model.ScreenLockEvent
import com.example.dancetimer.ui.screen.components.LockEventListSection
import com.example.dancetimer.ui.screen.components.StartButton

/**
 * 空闲（待开始）页面内容。
 *
 * 视觉层次：状态副标题 → 时钟占位 → 费用占位 → 规则选择 → 启动按钮 → 操作提示
 */
@Composable
fun TimerIdleContent(
    ruleName: String?,
    isBatteryOptimized: Boolean = false,
    recentLockEvents: List<ScreenLockEvent> = emptyList(),
    allRules: List<PricingRuleWithTiers> = emptyList(),
    selectedLockEventRuleId: Long? = null,
    defaultRuleId: Long? = null,
    showVolumeTip: Boolean = true,
    onStartClick: () -> Unit,
    onRulesClick: () -> Unit,
    onBatteryFixClick: () -> Unit = {},
    onLockEventRuleSelected: (Long) -> Unit = {},
    onStartFromLockEvent: (ScreenLockEvent) -> Unit = {},
    onFinishFromLockEvent: (ScreenLockEvent) -> Unit = {},
    onViewAllLockEvents: () -> Unit = {},
    usedLockEventIds: Set<Long> = emptySet(),
    onMarkLockEventUsed: (Long) -> Unit = {},
    onDismissVolumeTipOnce: () -> Unit = {},
    onDismissVolumeTipPermanently: () -> Unit = {}
) {
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme

    // ── 电池优化警告 ──
    if (isBatteryOptimized) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onBatteryFixClick() },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = colors.errorContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "后台运行受限",
                        style = typography.labelMedium,
                        color = colors.onErrorContainer
                    )
                    Text(
                        text = "通知栏可能无法实时更新，点击修复",
                        style = typography.bodySmall,
                        color = colors.onErrorContainer.copy(alpha = 0.8f),
                        fontSize = 11.sp
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    // ── 状态标题 ──
    Text(
        text = "准备就绪",
        style = typography.headlineLarge,
        color = colors.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(20.dp))

    // ── 时钟占位 ──
    Text(
        text = "0:00",
        style = typography.displayLarge,
        color = colors.onBackground
    )

    Spacer(modifier = Modifier.height(8.dp))

    // ── 费用占位 ──
    Text(
        text = "¥0",
        style = typography.displaySmall,
        color = colors.primary
    )

    Spacer(modifier = Modifier.height(36.dp))

    // ── 规则选择 ──
    if (ruleName != null) {
        FilledTonalButton(
            onClick = onRulesClick,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = colors.surfaceVariant,
                contentColor = colors.onSurface
            ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Icon(
                Icons.Outlined.AttachMoney,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "规则：$ruleName",
                style = typography.labelMedium
            )
        }
    } else {
        OutlinedButton(
            onClick = onRulesClick,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = colors.tertiary
            )
        ) {
            Text(
                text = "⚠ 请先配置计价规则",
                style = typography.labelMedium
            )
        }
    }

    Spacer(modifier = Modifier.height(40.dp))

    // ── 启动按钮 ──
    StartButton(onClick = onStartClick)

    Spacer(modifier = Modifier.height(28.dp))

    // ── 操作提示（可关闭） ──
    if (showVolumeTip) {
        var showDismissDialog by remember { mutableStateOf(false) }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = colors.surfaceVariant.copy(alpha = 0.5f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "💡",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 1.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "长按【音量+】启动计时 · 长按【音量-】停止",
                        style = typography.labelSmall,
                        color = colors.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "📱 锁屏 / 息屏后同样有效",
                        style = typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Italic,
                        color = colors.primary,
                        fontSize = 12.sp
                    )
                }
                IconButton(
                    onClick = { showDismissDialog = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "关闭提示",
                        modifier = Modifier.size(14.dp),
                        tint = colors.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }

        if (showDismissDialog) {
            AlertDialog(
                onDismissRequest = { showDismissDialog = false },
                title = { Text("关闭提示") },
                text = { Text("选择关闭方式") },
                confirmButton = {
                    TextButton(onClick = {
                        showDismissDialog = false
                        onDismissVolumeTipPermanently()
                    }) {
                        Text("永久关闭")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showDismissDialog = false
                        onDismissVolumeTipOnce()
                    }) {
                        Text("本次关闭")
                    }
                },
                containerColor = colors.surface,
                tonalElevation = 6.dp
            )
        }
    }

    // ── 锁屏事件列表（始终展示） ──
    Spacer(modifier = Modifier.height(32.dp))
    LockEventListSection(
        events = recentLockEvents,
        allRules = allRules,
        selectedRuleId = selectedLockEventRuleId,
        defaultRuleId = defaultRuleId,
        usedLockEventIds = usedLockEventIds,
        onMarkUsed = onMarkLockEventUsed,
        onRuleSelected = onLockEventRuleSelected,
        onStartFromEvent = onStartFromLockEvent,
        onFinishFromEvent = onFinishFromLockEvent,
        onViewAllLockEvents = onViewAllLockEvents
    )
}
