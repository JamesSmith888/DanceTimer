package com.example.dancetimer

import android.app.Application
import com.example.dancetimer.data.db.AppDatabase
import com.example.dancetimer.data.model.PriceTier
import com.example.dancetimer.data.model.PricingRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DanceTimerApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 首次启动时插入预设计价规则
        appScope.launch {
            seedDefaultRulesIfNeeded()
        }
    }

    /**
     * 如果数据库中没有任何计价规则，插入预设模板
     */
    private suspend fun seedDefaultRulesIfNeeded() {
        val dao = database.pricingRuleDao()
        val existing = dao.getAllRules().first()
        if (existing.isNotEmpty()) return

        // 预设规则1：4分20元（默认）
        val rule1Id = dao.insertRule(PricingRule(name = "4分20元", isDefault = true))
        dao.insertTiers(listOf(
            PriceTier(ruleId = rule1Id, durationMinutes = 4.0f, price = 20f, sortOrder = 0)
        ))

        // 预设规则2：3分10元
        val rule2Id = dao.insertRule(PricingRule(name = "3分10元"))
        dao.insertTiers(listOf(
            PriceTier(ruleId = rule2Id, durationMinutes = 3.0f, price = 10f, sortOrder = 0)
        ))

        // 预设规则3：1分5元
        val rule3Id = dao.insertRule(PricingRule(name = "1分5元"))
        dao.insertTiers(listOf(
            PriceTier(ruleId = rule3Id, durationMinutes = 1.0f, price = 5f, sortOrder = 0)
        ))
    }
}
