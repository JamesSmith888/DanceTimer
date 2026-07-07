package com.example.dancetimer.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.dancetimer.data.model.*
import com.example.dancetimer.ui.viewmodel.StatsViewModel
import com.example.dancetimer.util.CostCalculator

/**
 * 数据统计页。
 *
 * 卡片布局，从上到下依次展示：
 *  1. 消费概览 — 总次数 / 总时长 / 平均时长 / 最长单次
 *  2. 近14天消费 — 按日柱状图
 *  3. 活跃时段 — 0-23时热力圆点图（4行×6列）
 *  4. 规则使用分布 — 各计价规则的横向进度条
 *
 * 所有统计仅包含 cost > 0 的有效计费记录，与历史列表统计口径保持一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    navController: NavHostController,
    viewModel: StatsViewModel = viewModel()
) {
    val overview       by viewModel.overviewStats.collectAsState(initial = null)
    val last14Days     by viewModel.last14Days.collectAsState(initial = emptyList())
    val weekdayDist    by viewModel.weekdayDistribution.collectAsState(initial = emptyList())
    val rules          by viewModel.ruleUsage.collectAsState(initial = emptyList())

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "数据统计",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. 概览卡
            item {
                SummaryCard(overview)
            }

            // 2. 近14天消费柱状图
            item {
                Last14DaysCard(last14Days)
            }

            // 3. 星期消费分布
            item {
                WeekdayDistCard(weekdayDist)
            }

            // 4. 规则使用分布（无数据时隐藏整张卡）
            if (rules.isNotEmpty()) {
                item {
                    RuleUsageCard(rules)
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// ─── 通用卡片容器 ──────────────────────────────────────────────────────────────

@Composable
private fun StatCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun EmptyHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "暂无数据",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

// ─── 1. 消费概览卡 ─────────────────────────────────────────────────────────────────

@Composable
private fun SummaryCard(stats: OverviewStats?) {
    StatCard(title = "消费概览") {
        if (stats == null || stats.totalSessions == 0) {
            EmptyHint()
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MetricItem(
                        value    = "${stats.totalSessions}",
                        unit     = "次",
                        label    = "总次数",
                        modifier = Modifier.weight(1f)
                    )
                    MetricDivider()
                    MetricItem(
                        value    = "${stats.totalDurationMin}",
                        unit     = "分钟",
                        label    = "总时长",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MetricItem(
                        value    = "${stats.avgDurationMin}",
                        unit     = "分钟",
                        label    = "平均时长",
                        modifier = Modifier.weight(1f)
                    )
                    MetricDivider()
                    MetricItem(
                        value    = "${stats.maxDurationMin}",
                        unit     = "分钟",
                        label    = "最长单次",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricItem(
    value: String,
    unit: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = value,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = unit,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MetricDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(40.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    )
}

// ─── 2. 近14天消费柱状图 ────────────────────────────────────────────────────────

/**
 * 纯 Compose 布局实现的垂直柱状图，无需第三方图表库。
 *
 * 每根柱子是一个 `fillMaxHeight(fraction)` 的 Box，在固定高度 Row 中
 * 通过 `verticalAlignment = Alignment.Bottom` 实现底对齐。
 * 最小可见柱高为 4%，防止有记录但金额极小时柱子不可见。
 */
@Composable
private fun Last14DaysCard(data: List<DailyStats>) {
    StatCard(title = "近14天消费") {
        val hasData = data.any { it.cost > 0f }
        if (!hasData) {
            EmptyHint()
            return@StatCard
        }

        val maxCost  = data.maxOf { it.cost }
        val barColor = MaterialTheme.colorScheme.primary

        // 柱子区域
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            data.forEach { stat ->
                val rawFraction = if (maxCost > 0f) stat.cost / maxCost else 0f
                // cost > 0 时保证最小可见高度；cost = 0 时高度为 0
                val fraction = if (stat.cost > 0f) rawFraction.coerceAtLeast(0.04f) else 0f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (stat.cost > 0f) barColor else Color.Transparent)
                )
            }
        }

        // 日期标签行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            data.forEach { stat ->
                Text(
                    text = stat.label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─── 3. 按星期消费分布 ─────────────────────────────────────────────────────────

/**
 * 垂直柱状图：展示周一～周日的累计消费与次数。
 *
 * 柱高代表该星期总消费金额，柱内顶部数字为跳舞次数，
 * 一眼看出哪天最常去舞厅、消费最多。
 * 风格与"近14天消费"保持一致：纯 Compose 布局，无第三方图表库。
 */
@Composable
private fun WeekdayDistCard(data: List<WeekdayStats>) {
    StatCard(title = "按星期消费") {
        val hasData = data.any { it.totalCost > 0f }
        if (!hasData) {
            EmptyHint()
            return@StatCard
        }

        val maxCost  = data.maxOf { it.totalCost }
        val barColor = MaterialTheme.colorScheme.primary

        // 柱子区域
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            data.forEach { stat ->
                val rawFraction = if (maxCost > 0f) stat.totalCost / maxCost else 0f
                val fraction    = if (stat.totalCost > 0f) rawFraction.coerceAtLeast(0.06f) else 0f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (stat.totalCost > 0f) barColor else Color.Transparent),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (stat.sessionCount > 0) {
                        Text(
                            text       = "${stat.sessionCount}",
                            fontSize   = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color      = MaterialTheme.colorScheme.onPrimary,
                            textAlign  = TextAlign.Center,
                            modifier   = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }

        // 星期标签行（一～日）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            data.forEach { stat ->
                Text(
                    text       = stat.label,
                    modifier   = Modifier.weight(1f),
                    textAlign  = TextAlign.Center,
                    fontSize   = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color      = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─── 4. 规则使用分布 ───────────────────────────────────────────────────────────

/**
 * 各计价规则的横向进度条分布图。
 * 使用 Material3 [LinearProgressIndicator]，进度值为"该规则总消费 / 最高规则总消费"。
 * 按总消费降序排列，直观呈现最常用/最贵规则。
 */
@Composable
private fun RuleUsageCard(rules: List<RuleUsage>) {
    StatCard(title = "规则使用分布") {
        val maxCost = rules.maxOf { it.totalCost }.coerceAtLeast(0.01f)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            rules.forEach { rule ->
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = rule.ruleName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${CostCalculator.formatCost(rule.totalCost)}  ·  ${rule.sessionCount}次",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    LinearProgressIndicator(
                        progress        = { rule.totalCost / maxCost },
                        modifier        = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color           = MaterialTheme.colorScheme.primary,
                        trackColor      = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap       = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
            }
        }
    }
}
