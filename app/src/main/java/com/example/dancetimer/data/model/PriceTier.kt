package com.example.dancetimer.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 价格档位 — 隶属于某个计价规则
 * 例如：3分钟=10元, 3.5分钟=20元, 4分钟=20元
 */
@Entity(
    tableName = "price_tiers",
    foreignKeys = [
        ForeignKey(
            entity = PricingRule::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("ruleId")]
)
data class PriceTier(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val ruleId: Long,
    /** 时长（分钟），支持小数如 3.5 */
    val durationMinutes: Float,
    /** 该档位价格（元） */
    val price: Float,
    /** 排序序号，用于按时长排序 */
    val sortOrder: Int = 0
)
