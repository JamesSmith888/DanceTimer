package com.example.dancetimer.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.dancetimer.data.model.PriceTier
import com.example.dancetimer.data.model.ScreenLockEvent
import com.example.dancetimer.ui.screen.components.LockEventConfirmDialog
import com.example.dancetimer.ui.viewmodel.HomeViewModel
import com.example.dancetimer.util.CostCalculator
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.*

/**
 * 锁屏事件历史记录页 — 展示所有锁屏事件。
 *
 * - 按小时分组
 * - 同一分钟内的事件折叠
 * - 点击可弹出从锁屏时间开始计时 / 以该记录计费 对话框
 * - 支持删除单条记录 / 清除全部
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockEventHistoryScreen(
    navController: NavHostController,
    viewModel: HomeViewModel = viewModel()
) {
    // 使用分页数据代替全量加载
    val allEvents by viewModel.pagedLockEvents.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val hasMore by viewModel.hasMoreEvents.collectAsState()
    val totalCount by viewModel.totalLockEventCount.collectAsState(initial = 0)
    val allRulesWithTiers by viewModel.allRulesWithTiers.collectAsState(initial = emptyList())
    val defaultRule by viewModel.defaultRule.collectAsState(initial = null)
    var showClearDialog by remember { mutableStateOf(false) }
    var confirmEvent by remember { mutableStateOf<ScreenLockEvent?>(null) }

    // 进入页面时加载第一页
    LaunchedEffect(Unit) {
        viewModel.loadLockEventsPage(reset = true)
    }

    val hourGroupFormat = remember { SimpleDateFormat("yyyy-MM-dd HH", Locale.getDefault()) }
    val minuteFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // 确定当前使用的规则
    val activeRule = allRulesWithTiers.find { it.rule.isDefault }
        ?: allRulesWithTiers.firstOrNull()
    val tiers = activeRule?.sortedTiers ?: emptyList()

    // 按小时分组
    val grouped by remember(allEvents) {
        derivedStateOf {
            allEvents.groupBy { event ->
                hourGroupFormat.format(Date(event.timestamp))
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "锁屏记录",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (totalCount > 0) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(
                                Icons.Filled.DeleteSweep,
                                "清除全部",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        if (allEvents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "暂无锁屏记录",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val listState = rememberLazyListState()

            // 检测滚动到底部，自动加载更多
            LaunchedEffect(listState) {
                snapshotFlow {
                    val layoutInfo = listState.layoutInfo
                    val totalItems = layoutInfo.totalItemsCount
                    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lastVisible to totalItems
                }.collectLatest { (lastVisible, totalItems) ->
                    if (totalItems > 0 && lastVisible >= totalItems - 3) {
                        viewModel.loadLockEventsPage()
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                grouped.forEach { (hourKey, hourEvents) ->
                    // 小时分组标题
                    item(key = "header_$hourKey") {
                        val displayHeader = formatHourHeader(hourKey)
                        Text(
                            text = "$displayHeader · ${hourEvents.size}条",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp)
                        )
                    }

                    // 同一小时内按分钟折叠
                    item(key = "card_$hourKey") {
                        val minuteGroups = remember(hourEvents) {
                            hourEvents.groupBy { minuteFormat.format(Date(it.timestamp)) }
                                .entries.toList()
                                .sortedByDescending { it.value.first().timestamp }
                        }

                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                minuteGroups.forEachIndexed { index, (minuteKey, groupEvents) ->
                                    MinuteGroupRow(
                                        minuteKey = minuteKey,
                                        events = groupEvents,
                                        tiers = tiers,
                                        timeFormat = timeFormat,
                                        onEventClick = { confirmEvent = it },
                                        onDeleteEvent = { viewModel.deleteLockEvent(it.id) }
                                    )
                                    if (index < minuteGroups.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 加载更多指示器
                if (isLoadingMore) {
                    item(key = "loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                } else if (!hasMore && allEvents.isNotEmpty()) {
                    item(key = "no_more") {
                        Text(
                            text = "— 已加载全部 ${allEvents.size} 条记录 —",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }

        // 清除全部确认对话框
        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text("清除全部锁屏记录") },
                text = { Text("确定要删除所有锁屏记录吗？此操作不可撤销。") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.clearAllLockEvents()
                        showClearDialog = false
                    }) {
                        Text("删除全部", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }

        // 点击事件确认对话框
        confirmEvent?.let { event ->
            LockEventConfirmDialog(
                event = event,
                tiers = tiers,
                onStartTimer = {
                    viewModel.startTimerFromLockEvent(event)
                    confirmEvent = null
                    navController.popBackStack()
                },
                onFinishBilling = {
                    viewModel.finishFromLockEvent(event)
                    confirmEvent = null
                    navController.popBackStack()
                },
                onDismiss = { confirmEvent = null }
            )
        }
    }
}

// ─── 按分钟折叠的行 ───

@Composable
private fun MinuteGroupRow(
    minuteKey: String,
    events: List<ScreenLockEvent>,
    tiers: List<PriceTier>,
    timeFormat: SimpleDateFormat,
    onEventClick: (ScreenLockEvent) -> Unit,
    onDeleteEvent: (ScreenLockEvent) -> Unit
) {
    if (events.size == 1) {
        // 单条事件直接展示
        val event = events.first()
        HistoryEventRow(
            event = event,
            timeFormat = timeFormat,
            tiers = tiers,
            onClick = { onEventClick(event) },
            onDelete = { onDeleteEvent(event) }
        )
    } else {
        // 多条事件折叠
        var expanded by remember { mutableStateOf(false) }
        val representative = events.first()
        val now = System.currentTimeMillis()
        val ageSeconds = ((now - representative.timestamp) / 1000).toInt().coerceAtLeast(0)
        val durationStr = "已过 ${CostCalculator.formatDurationChinese(ageSeconds)}"

        val colors = MaterialTheme.colorScheme
        val typography = MaterialTheme.typography

        // 折叠摘要行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 时间（高亮）
            Text(
                text = minuteKey,
                style = typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = colors.primary,
                modifier = Modifier.width(52.dp)
            )

            // 次数标记
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = colors.primaryContainer,
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Text(
                    text = "${events.size}次",
                    style = typography.labelSmall,
                    color = colors.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 10.sp
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 已过时长
            Text(
                text = durationStr,
                style = typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp)
            )

            // 展开/折叠图标
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(18.dp),
                tint = colors.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }

        // 展开后的子事件列表
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                events.forEach { event ->
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        color = colors.outlineVariant.copy(alpha = 0.1f)
                    )
                    HistoryEventRow(
                        event = event,
                        timeFormat = timeFormat,
                        tiers = tiers,
                        onClick = { onEventClick(event) },
                        onDelete = { onDeleteEvent(event) },
                        indented = true
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryEventRow(
    event: ScreenLockEvent,
    timeFormat: SimpleDateFormat,
    tiers: List<PriceTier>,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    indented: Boolean = false
) {
    val now = System.currentTimeMillis()
    val ageSeconds = ((now - event.timestamp) / 1000).toInt().coerceAtLeast(0)
    val timeStr = timeFormat.format(Date(event.timestamp))
    val durationStr = "已过 ${CostCalculator.formatDurationChinese(ageSeconds)}"

    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = if (indented) 28.dp else 14.dp,
                end = 14.dp,
                top = 10.dp,
                bottom = 10.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 时间（高亮）
        Text(
            text = timeStr,
            style = typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = colors.primary,
            modifier = Modifier.width(72.dp)
        )

        // 已过时长
        Text(
            text = durationStr,
            style = typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )

        // 删除按钮
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "删除",
                modifier = Modifier.size(18.dp),
                tint = colors.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

// ─── 工具方法 ───

/**
 * 将 "yyyy-MM-dd HH" 小时分组 key 转换为友好显示，如 "今天 14:00-15:00"、"昨天 09:00-10:00"、"3月9日 20:00-21:00"
 */
private fun formatHourHeader(hourKey: String): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH", Locale.getDefault())
    val dateSdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val date = sdf.parse(hourKey) ?: return hourKey

    val cal = Calendar.getInstance()
    val todayStr = dateSdf.format(cal.time)
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val yesterdayStr = dateSdf.format(cal.time)

    val dateStr = hourKey.substring(0, 10) // yyyy-MM-dd
    val hour = hourKey.substring(11).toIntOrNull() ?: 0
    val nextHour = (hour + 1) % 24
    val timeRange = String.format("%02d:00–%02d:00", hour, nextHour)

    val dayLabel = when (dateStr) {
        todayStr -> "今天"
        yesterdayStr -> "昨天"
        else -> {
            val displayFormat = SimpleDateFormat("M月d日", Locale.getDefault())
            displayFormat.format(dateSdf.parse(dateStr) ?: date)
        }
    }
    return "$dayLabel $timeRange"
}
