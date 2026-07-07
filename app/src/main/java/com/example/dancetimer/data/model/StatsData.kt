package com.example.dancetimer.data.model

/**
 * 统计图所需的聚合数据模型。
 *
 * 这些模型仅用于 UI 展示层，不存入数据库，由 [com.example.dancetimer.ui.viewmodel.StatsViewModel]
 * 从 [DanceRecord] 实时聚合计算，避免在 DAO 层增加复杂 SQL 聚合语句。
 */

/** 单日统计，用于近14天费用柱状图 */
data class DailyStats(
    /** 显示标签（"今"、"昨"、日期数字、或 "M/D" 跨月时） */
    val label: String,
    /** 当日有效计费金额（元，cost > 0 的记录之和） */
    val cost: Float
)

/** 按星期消费统计，用于星期消费分布图 */
data class WeekdayStats(
    /** 星期标签（“一”～“日”，对应周一～周日） */
    val label: String,
    /** 该星期的有效计费次数 */
    val sessionCount: Int,
    /** 该星期累计消费（元） */
    val totalCost: Float
)

/** 计价规则使用统计，用于规则分布横向进度条 */
data class RuleUsage(
    val ruleName: String,
    val totalCost: Float,
    val sessionCount: Int
)

/** 总览指标，用于概览卡 */
data class OverviewStats(
    /** 有效计费次数（cost > 0） */
    val totalSessions: Int,
    /** 所有有效记录总时长（分钟） */
    val totalDurationMin: Int,
    /** 平均单次时长（分钟） */
    val avgDurationMin: Int,
    /** 最长单次时长（分钟） */
    val maxDurationMin: Int,
    /** 累计总消费（元） */
    val totalCost: Float
)
