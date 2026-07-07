package com.example.dancetimer.util

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * 日期边界计算工具 — 所有统计查询的时间范围均通过此对象计算。
 *
 * ## 设计原则
 * - 所有方法在**调用时**基于设备当前时区和时间计算，调用方不得缓存返回值；
 *   每个查询周期应重新调用（参见 HistoryViewModel 的 dateTicker 机制）。
 * - 使用 `java.time`（通过 AGP Core Library Desugaring 支持 API 24+），
 *   避免 `java.util.Calendar` 的字段歧义问题。
 *
 * ## 关键语义：startOfWeekInMonth
 * 当日历周跨越月份边界时（例如当月1日是周三，而周从上月28日周日开始），
 * [startOfWeek] 会早于 [startOfMonth]，导致"本周"包含上月记录，
 * 在 UI 上呈现"本周 > 本月"的反直觉结果。
 * [startOfWeekInMonth] 取两者的较大值，将"本周"下界限定在当月起始日内，
 * 确保 weekCost ≤ monthCost 在任意日期下恒成立。
 */
object DateRangeCalculator {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** 今日 00:00:00.000（设备本地时区）。 */
    fun startOfToday(): Long = LocalDate.now().atStartOfDayMs()

    /**
     * 明日 00:00:00.000（今日查询的不含上界）。
     *
     * 用法：`WHERE startTime >= startOfToday() AND startTime < startOfTomorrow()`
     * 可完整覆盖今日所有毫秒，且天然排除未来记录。
     */
    fun startOfTomorrow(): Long = LocalDate.now().plusDays(1).atStartOfDayMs()

    /**
     * 当前日历周的第一天 00:00:00.000（遵循设备语言区域的周起始日）。
     *
     * 注意：当当前周跨越月边界时，此值可能早于当月第一天。
     * 统计展示请使用 [startOfWeekInMonth]。
     */
    fun startOfWeek(): Long {
        val weekFields = WeekFields.of(Locale.getDefault())
        return LocalDate.now().with(weekFields.dayOfWeek(), 1).atStartOfDayMs()
    }

    /** 当月第一天 00:00:00.000。 */
    fun startOfMonth(): Long = LocalDate.now().withDayOfMonth(1).atStartOfDayMs()

    /**
     * 过去30天的起始时间 00:00:00.000（含今日共30天）。
     *
     * 用法：`WHERE startTime >= startOf30DaysAgo() AND startTime < startOfTomorrow()`
     * 覆盖从29天前（含）到今日（含）共30个自然日，与自然月无关。
     */
    fun startOf30DaysAgo(): Long = LocalDate.now().minusDays(29).atStartOfDayMs()

    /**
     * 本周下界，已限定在当月范围内：`max(startOfWeek(), startOfMonth())`。
     *
     * 当日历周开始于上月时，此方法返回当月第一天，从而保证
     * "本周"统计区间是"本月"统计区间的子集（weekCost ≤ monthCost）。
     *
     * 在 UI 统计卡片中始终应使用此方法而非 [startOfWeek]。
     */
    fun startOfWeekInMonth(): Long = maxOf(startOfWeek(), startOfMonth())

    /**
     * 距离下一个午夜的毫秒数（用于 HistoryViewModel dateTicker 的延迟调度）。
     * 保证最小返回 0，调用方应在此基础上添加适当的 guard margin。
     */
    fun msUntilTomorrow(): Long = (startOfTomorrow() - System.currentTimeMillis()).coerceAtLeast(0L)

    // ---------- 内部扩展 ----------

    private fun LocalDate.atStartOfDayMs(): Long =
        atStartOfDay(zone).toInstant().toEpochMilli()
}
