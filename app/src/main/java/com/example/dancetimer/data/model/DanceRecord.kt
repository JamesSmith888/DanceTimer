package com.example.dancetimer.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 跳舞历史记录
 */
@Entity(tableName = "dance_records")
data class DanceRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 开始时间戳 (毫秒, System.currentTimeMillis) */
    val startTime: Long,
    /** 结束时间戳 (毫秒) */
    val endTime: Long,
    /** 实际跳舞时长 (秒) */
    val durationSeconds: Int,
    /** 计算出的费用 (元) */
    val cost: Float,
    /** 使用的计价规则名称 (快照，不关联外键) */
    val pricingRuleName: String,
    /** 使用的计价规则ID (便于追溯) */
    val pricingRuleId: Long
)
