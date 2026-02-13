package com.example.dancetimer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dancetimer.data.db.AppDatabase
import com.example.dancetimer.data.model.PriceTier
import com.example.dancetimer.data.model.PricingRule
import com.example.dancetimer.data.model.PricingRuleWithTiers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PricingRuleViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).pricingRuleDao()

    val allRulesWithTiers: Flow<List<PricingRuleWithTiers>> = dao.getAllRulesWithTiers()

    fun deleteRule(rule: PricingRule) {
        viewModelScope.launch {
            dao.deleteRule(rule)
        }
    }

    fun setAsDefault(ruleId: Long) {
        viewModelScope.launch {
            dao.setAsDefault(ruleId)
        }
    }
}

/**
 * 编辑单个计价规则的 ViewModel（单档位模式）
 */
class EditRuleViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).pricingRuleDao()

    private val _ruleName = MutableStateFlow("")
    val ruleName: StateFlow<String> = _ruleName.asStateFlow()

    // 单档位
    private val _singleTier = MutableStateFlow(EditableTier())
    val singleTier: StateFlow<EditableTier> = _singleTier.asStateFlow()

    // 兼容旧代码：tiers 列表始终只有一个元素
    val tiers: StateFlow<List<EditableTier>> = _singleTier.map { listOf(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(EditableTier()))

    private var existingRuleId: Long = -1L
    private var isDefault: Boolean = false

    /** 加载已有规则（编辑模式） */
    fun loadRule(ruleId: Long) {
        if (ruleId <= 0) return
        viewModelScope.launch {
            val ruleWithTiers = dao.getRuleWithTiers(ruleId) ?: return@launch
            existingRuleId = ruleWithTiers.rule.id
            isDefault = ruleWithTiers.rule.isDefault
            _ruleName.value = ruleWithTiers.rule.name
            // 取第一个档位（单档位模式）
            val firstTier = ruleWithTiers.sortedTiers.firstOrNull()
            if (firstTier != null) {
                _singleTier.value = EditableTier(
                    id = firstTier.id,
                    durationText = firstTier.durationMinutes.toString(),
                    priceText = firstTier.price.toLong().toString()
                )
            }
        }
    }

    fun updateName(name: String) {
        _ruleName.value = name
    }

    fun updateSingleTier(tier: EditableTier) {
        _singleTier.value = tier
    }

    // 兼容旧代码的方法
    fun addTier() { /* 单档位模式不需要 */ }
    fun updateTier(index: Int, tier: EditableTier) { _singleTier.value = tier }
    fun removeTier(index: Int) { /* 单档位模式不需要 */ }

    /** 保存规则（新建或更新） */
    fun save(onDone: () -> Unit) {
        val name = _ruleName.value.trim()
        if (name.isBlank()) return

        viewModelScope.launch {
            val tier = buildSingleTier(0) ?: return@launch

            if (existingRuleId > 0) {
                // 更新
                dao.updateRule(PricingRule(id = existingRuleId, name = name, isDefault = isDefault))
                dao.deleteTiersByRuleId(existingRuleId)
                dao.insertTiers(listOf(tier.copy(ruleId = existingRuleId)))
            } else {
                // 新建
                val newId = dao.insertRule(PricingRule(name = name))
                dao.insertTiers(listOf(tier.copy(ruleId = newId)))
                // 如果是第一个规则，自动设为默认
                val allRules = dao.getAllRules().first()
                if (allRules.size == 1) {
                    dao.setAsDefault(newId)
                }
            }
            onDone()
        }
    }

    private fun buildSingleTier(ruleId: Long): PriceTier? {
        val editable = _singleTier.value
        val duration = editable.durationText.toFloatOrNull() ?: return null
        val price = editable.priceText.toFloatOrNull() ?: return null
        return PriceTier(
            ruleId = ruleId,
            durationMinutes = duration,
            price = price,
            sortOrder = 0
        )
    }
}

/** UI 层的可编辑档位 */
data class EditableTier(
    val id: Long = 0,
    val durationText: String = "",
    val priceText: String = ""
)
