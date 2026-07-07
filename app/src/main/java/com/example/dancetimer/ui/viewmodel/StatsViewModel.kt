package com.example.dancetimer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dancetimer.data.db.AppDatabase
import com.example.dancetimer.data.model.*
import com.example.dancetimer.util.DateRangeCalculator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 统计页 ViewModel。
 *
 * ## 设计原则
 * - 所有聚合在 ViewModel 层通过 Kotlin 集合操作完成，DAO 层只暴露原始 Flow，
 *   避免在 SQL 层硬编码日期计算。
 * - 与 HistoryViewModel 保持一致：使用 `dateTicker + flatMapLatest` 驱动
 *   近14天图表刷新，确保跨日后时间边界自动更新；
 *   时段分布、规则分布为全时段统计，由数据变更自动触发重算。
 * - 仅统计 cost > 0 的有效计费记录，排除误计时（RESULT_CANCELLED）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).danceRecordDao()

    /**
     * 午夜触发器（与 HistoryViewModel 共用相同模式）。
     * 使近14天统计在跨日后自动刷新时间坐标轴，而非沿用构造时快照。
     */
    private val dateTicker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(DateRangeCalculator.msUntilTomorrow().coerceAtLeast(60_000L))
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** 全部有效计费记录（cost > 0），多个统计维度的基础数据源。 */
    private val validRecords: Flow<List<com.example.dancetimer.data.model.DanceRecord>> =
        dao.getAll()
            .map { list -> list.filter { it.cost > 0f } }
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    // ─── 1. 概览统计 ───────────────────────────────────────────────────────────

    /** 总览指标：总次数、总时长、平均时长、最长单次、总消费。 */
    val overviewStats: Flow<OverviewStats> = validRecords.map { records ->
        if (records.isEmpty()) return@map OverviewStats(0, 0, 0, 0, 0f)
        val totalDurMin = records.sumOf { it.durationSeconds } / 60
        val avgDurMin   = if (records.isNotEmpty()) totalDurMin / records.size else 0
        val maxDurMin   = records.maxOf { it.durationSeconds } / 60
        val totalCost   = records.sumOf { it.cost.toDouble() }.toFloat()
        OverviewStats(records.size, totalDurMin, avgDurMin, maxDurMin, totalCost)
    }

    // ─── 2. 近14天每日消费柱状图 ────────────────────────────────────────────────

    /**
     * 近14天（含今日）每日消费列表，通过 dateTicker 在每日午夜自动更新日期坐标轴。
     *
     * 返回长度固定为14的列表（无记录的日期 cost = 0），
     * 便于 UI 直接按索引渲染，无需额外补全逻辑。
     */
    val last14Days: Flow<List<DailyStats>> = dateTicker.flatMapLatest {
        validRecords.map { records ->
            val zone  = ZoneId.systemDefault()
            val today = LocalDate.now()
            // 按本地日期聚合
            val grouped = records.groupBy { r ->
                Instant.ofEpochMilli(r.startTime).atZone(zone).toLocalDate()
            }
            (13 downTo 0).map { offset ->
                val date  = today.minusDays(offset.toLong())
                val cost  = grouped[date]?.sumOf { it.cost.toDouble() }?.toFloat() ?: 0f
                val label = when {
                    offset == 0           -> "今"
                    offset == 1           -> "昨"
                    date.dayOfMonth == 1  -> "${date.monthValue}/${date.dayOfMonth}"
                    else                  -> "${date.dayOfMonth}"
                }
                DailyStats(label, cost)
            }
        }
    }

    // ─── 3. 按星期消费分布 ─────────────────────────────────────────────────────────────────

    /**
     * 按星期一～日聚合的累计消费与次数。
     *
     * 使用全量有效记录统计，直观反映用户在舞厅的习惯性出行规律。
     * 返回长度固定为 7 的列表（周一→周日），无记录的星期 cost = 0。
     */
    val weekdayDistribution: Flow<List<WeekdayStats>> = validRecords.map { records ->
        val zone   = ZoneId.systemDefault()
        val costs  = FloatArray(7)
        val counts = IntArray(7)
        records.forEach { r ->
            // DayOfWeek.MONDAY.value=1 … SUNDAY.value=7 → 映射为 index 0..6
            val idx = Instant.ofEpochMilli(r.startTime).atZone(zone).dayOfWeek.value - 1
            costs[idx]  += r.cost
            counts[idx]++
        }
        val labels = listOf("一", "二", "三", "四", "五", "六", "日")
        labels.mapIndexed { i, label -> WeekdayStats(label, counts[i], costs[i]) }
    }

    // ─── 4. 计价规则使用分布 ────────────────────────────────────────────────────

    /** 按计价规则聚合的消费与次数，按总消费降序排列。 */
    val ruleUsage: Flow<List<RuleUsage>> = validRecords.map { records ->
        records.groupBy { it.pricingRuleName }
            .map { (name, recs) ->
                RuleUsage(
                    ruleName     = name,
                    totalCost    = recs.sumOf { it.cost.toDouble() }.toFloat(),
                    sessionCount = recs.size
                )
            }
            .sortedByDescending { it.totalCost }
    }
}
