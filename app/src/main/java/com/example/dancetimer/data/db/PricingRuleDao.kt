package com.example.dancetimer.data.db

import androidx.room.*
import com.example.dancetimer.data.model.PriceTier
import com.example.dancetimer.data.model.PricingRule
import com.example.dancetimer.data.model.PricingRuleWithTiers
import kotlinx.coroutines.flow.Flow

@Dao
interface PricingRuleDao {

    // ---- PricingRule ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: PricingRule): Long

    @Update
    suspend fun updateRule(rule: PricingRule)

    @Delete
    suspend fun deleteRule(rule: PricingRule)

    @Query("SELECT * FROM pricing_rules ORDER BY createdAt ASC")
    fun getAllRules(): Flow<List<PricingRule>>

    @Transaction
    @Query("SELECT * FROM pricing_rules ORDER BY createdAt ASC")
    fun getAllRulesWithTiers(): Flow<List<PricingRuleWithTiers>>

    @Transaction
    @Query("SELECT * FROM pricing_rules WHERE id = :ruleId")
    suspend fun getRuleWithTiers(ruleId: Long): PricingRuleWithTiers?

    @Transaction
    @Query("SELECT * FROM pricing_rules WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultRuleWithTiers(): PricingRuleWithTiers?

    @Transaction
    @Query("SELECT * FROM pricing_rules WHERE isDefault = 1 LIMIT 1")
    fun getDefaultRuleWithTiersFlow(): Flow<PricingRuleWithTiers?>

    @Query("UPDATE pricing_rules SET isDefault = 0")
    suspend fun clearAllDefaults()

    @Query("UPDATE pricing_rules SET isDefault = 1 WHERE id = :ruleId")
    suspend fun setDefault(ruleId: Long)

    /** 设为默认（事务：先清除所有默认，再设新默认） */
    @Transaction
    suspend fun setAsDefault(ruleId: Long) {
        clearAllDefaults()
        setDefault(ruleId)
    }

    // ---- PriceTier ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTier(tier: PriceTier): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTiers(tiers: List<PriceTier>)

    @Update
    suspend fun updateTier(tier: PriceTier)

    @Delete
    suspend fun deleteTier(tier: PriceTier)

    @Query("DELETE FROM price_tiers WHERE ruleId = :ruleId")
    suspend fun deleteTiersByRuleId(ruleId: Long)

    @Query("SELECT * FROM price_tiers WHERE ruleId = :ruleId ORDER BY durationMinutes ASC")
    fun getTiersByRuleId(ruleId: Long): Flow<List<PriceTier>>
}
