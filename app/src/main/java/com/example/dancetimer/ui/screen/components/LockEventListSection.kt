package com.example.dancetimer.ui.screen.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dancetimer.data.model.PriceTier
import com.example.dancetimer.data.model.PricingRuleWithTiers
import com.example.dancetimer.data.model.ScreenLockEvent
import com.example.dancetimer.util.CostCalculator
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 锁屏事件列表区域 — 在空闲页展示最近锁屏时间（不折叠，最多 3 条）。
 *
 * - 默认展示前 3 条
 * - 超过 3 条时显示"查看更多"链接
 * - 引导用户"忘记计时？"场景
 */
@Composable
fun LockEventListSection(
    events: List<ScreenLockEvent>,
    allRules: List<PricingRuleWithTiers>,
    selectedRuleId: Long?,
    defaultRuleId: Long?,
    onRuleSelected: (Long) -> Unit,
    onStartFromEvent: (ScreenLockEvent) -> Unit,
    onFinishFromEvent: (ScreenLockEvent) -> Unit = {},
    onViewAllLockEvents: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (events.isEmpty()) return

    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    // 确定当前使用的规则
    val effectiveRuleId = selectedRuleId ?: defaultRuleId
    val activeRule = allRules.find { it.rule.id == effectiveRuleId }
        ?: allRules.find { it.rule.isDefault }
        ?: allRules.firstOrNull()
    val tiers = activeRule?.sortedTiers ?: emptyList()

    // 每 30 秒刷新一次已过时间
    var refreshTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            refreshTick = System.currentTimeMillis()
        }
    }

    // 只显示最近 1 小时内的事件
    val recentEvents = remember(events, refreshTick) {
        val cutoff = System.currentTimeMillis() - 3600 * 1000L
        events.filter { it.timestamp >= cutoff }
    }

    if (recentEvents.isEmpty()) return

    // 默认最多展示 3 条
    val displayEvents = recentEvents.take(3)
    val hasMore = recentEvents.size > 3

    Column(modifier = modifier.fillMaxWidth()) {
        // ── 标题行 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.LockClock,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = colors.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = "忘记计时了？",
                    style = typography.labelMedium,
                    color = colors.onSurfaceVariant.copy(alpha = 0.8f)
                )
                Text(
                    text = "以下是最近的锁屏记录，可用于补录计时",
                    style = typography.bodySmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 规则切换
            if (allRules.size > 1) {
                RuleChipSelector(
                    rules = allRules,
                    selectedRuleId = activeRule?.rule?.id,
                    onSelected = onRuleSelected
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── 事件列表（平铺，最多 3 条） ──
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = colors.surfaceVariant.copy(alpha = 0.4f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                displayEvents.forEachIndexed { index, event ->
                    LockEventRow(
                        event = event,
                        tiers = tiers,
                        currentTimeMillis = refreshTick,
                        onStartTimer = { onStartFromEvent(event) },
                        onFinishBilling = { onFinishFromEvent(event) }
                    )
                    if (index < displayEvents.lastIndex || hasMore) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = colors.outlineVariant.copy(alpha = 0.15f)
                        )
                    }
                }

                // 查看更多
                if (hasMore) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onViewAllLockEvents)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "查看全部 ${recentEvents.size} 条记录",
                            style = typography.labelSmall,
                            color = colors.primary
                        )
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = colors.primary
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单条锁屏事件行 — 内联"计费""计时"操作按钮
 */
@Composable
private fun LockEventRow(
    event: ScreenLockEvent,
    tiers: List<PriceTier>,
    currentTimeMillis: Long,
    onStartTimer: () -> Unit,
    onFinishBilling: () -> Unit
) {
    val ageMs = currentTimeMillis - event.timestamp
    val ageSeconds = (ageMs / 1000).toInt().coerceAtLeast(0)
    val cost = CostCalculator.calculate(ageSeconds, tiers)
    val songCount = CostCalculator.getSongCount(ageSeconds, tiers)

    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        .format(Date(event.timestamp))
    val durationStr = "已过 ${CostCalculator.formatDurationChinese(ageSeconds)}"
    val costStr = CostCalculator.formatCost(cost)
    val songStr = if (songCount > 0) "${songCount}曲" else "未满1曲"

    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：时间 + 摘要信息
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeStr,
                    style = typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = durationStr,
                    style = typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$songStr · $costStr",
                style = typography.bodySmall,
                color = colors.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        // 右侧：内联操作按钮
        TextButton(
            onClick = onFinishBilling,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text(
                text = "计费",
                style = typography.labelSmall,
                fontSize = 12.sp
            )
        }
        TextButton(
            onClick = onStartTimer,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text(
                text = "计时",
                style = typography.labelSmall,
                fontSize = 12.sp
            )
        }
    }
}

/**
 * 规则切换小芯片
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleChipSelector(
    rules: List<PricingRuleWithTiers>,
    selectedRuleId: Long?,
    onSelected: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = rules.find { it.rule.id == selectedRuleId }?.rule?.name ?: "规则"

    Box {
        FilterChip(
            selected = false,
            onClick = { expanded = true },
            label = {
                Text(
                    text = selectedName,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp
                )
            },
            modifier = Modifier.height(28.dp)
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            rules.forEach { rule ->
                DropdownMenuItem(
                    text = { Text(rule.rule.name) },
                    onClick = {
                        onSelected(rule.rule.id)
                        expanded = false
                    },
                    leadingIcon = if (rule.rule.id == selectedRuleId) {
                        { Text("✓", color = MaterialTheme.colorScheme.primary) }
                    } else null
                )
            }
        }
    }
}

/**
 * 锁屏事件确认对话框 — 供历史页等场景使用。
 */
@Composable
fun LockEventConfirmDialog(
    event: ScreenLockEvent,
    tiers: List<PriceTier>,
    onStartTimer: () -> Unit,
    onFinishBilling: () -> Unit,
    onDismiss: () -> Unit
) {
    val ageSeconds = ((System.currentTimeMillis() - event.timestamp) / 1000).toInt()
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        .format(Date(event.timestamp))
    val durationStr = CostCalculator.formatDurationChinese(ageSeconds)
    val cost = CostCalculator.calculate(ageSeconds, tiers)
    val songCount = CostCalculator.getSongCount(ageSeconds, tiers)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
        title = { Text("从锁屏时间开始计时") },
        text = {
            Text("从 $timeStr 开始回溯计时\n已过 $durationStr · ${songCount}曲 · ${CostCalculator.formatCost(cost)}")
        },
        confirmButton = {
            TextButton(onClick = onStartTimer) {
                Text("开始计时")
            }
        },
        dismissButton = {
            TextButton(onClick = onFinishBilling) {
                Text("以该记录计费")
            }
        }
    )
}
