package com.example.dancetimer.data.model

import androidx.room.Embedded
import androidx.room.Relation

/**
 * 计价规则 + 其所有价格档位（Room 关联查询）
 */
data class PricingRuleWithTiers(
    @Embedded
    val rule: PricingRule,
    @Relation(
        parentColumn = "id",
        entityColumn = "ruleId"
    )
    val tiers: List<PriceTier>
) {
    /** 按时长升序排列的档位 */
    val sortedTiers: List<PriceTier>
        get() = tiers.sortedBy { it.durationMinutes }
}
