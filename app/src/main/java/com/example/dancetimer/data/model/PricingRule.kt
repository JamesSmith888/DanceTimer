package com.example.dancetimer.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 计价规则 — 一套价格方案（如"普通舞厅"、"高端舞厅"）
 */
@Entity(tableName = "pricing_rules")
data class PricingRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
