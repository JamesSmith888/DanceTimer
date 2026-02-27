package com.example.dancetimer.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 跳舞历史记录
 *
 * [triggerType]              触发来源：[TRIGGER_MANUAL] 手动 / [TRIGGER_AUTO] 息屏自动
 * [autoStartResult]          自动计时结果（triggerType=auto 时有值）:
 *                              [RESULT_CONFIRMED_USER]  用户主动点击"继续计时"
 *                              [RESULT_CONFIRMED_AUTO]  首曲计费线自动确认
 *                              [RESULT_CANCELLED]       用户取消（误计时）
 * [cancelledDurationSeconds] 误计时时已运行时长（秒），仅 RESULT_CANCELLED 时有值
 * [screenOffDelaySeconds]    锁屏延迟等待配置快照（秒），仅自动触发时有值
 */
@Entity(tableName = "dance_records")
data class DanceRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 开始时间戳 (毫秒, System.currentTimeMillis) */
    val startTime: Long,
    /** 结束时间戳 (毫秒) */
    val endTime: Long,
    /** 实际跳舞时长（秒）；误计时记录为取消前已累计时长 */
    val durationSeconds: Int,
    /** 计算费用（元）；误计时记录为 0f */
    val cost: Float,
    /** 使用的计价规则名称（快照，不关联外键） */
    val pricingRuleName: String,
    /** 使用的计价规则 ID */
    val pricingRuleId: Long,
    /** 触发来源 */
    val triggerType: String = TRIGGER_MANUAL,
    /** 自动计时结果；手动触发记录为 null */
    val autoStartResult: String? = null,
    /** 误计时已运行时长（秒）；仅 autoStartResult = RESULT_CANCELLED 时有值 */
    val cancelledDurationSeconds: Int = 0,
    /** 锁屏延迟等待秒数配置快照；仅自动触发时有值 */
    val screenOffDelaySeconds: Int = 0
) {
    companion object {
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_AUTO   = "auto_screen_off"
        /** 从锁屏事件回溯启动 */
        const val TRIGGER_LOCK_EVENT = "lock_event_backdate"

        /** 用户在通知或 App 内主动确认 */
        const val RESULT_CONFIRMED_USER = "confirmed_user"
        /** 到达首曲计费线，系统自动确认 */
        const val RESULT_CONFIRMED_AUTO = "confirmed_auto"
        /** 用户取消或重置（误计时） */
        const val RESULT_CANCELLED      = "cancelled"
    }

    /** 是否为误计时记录 */
    val isCancelled: Boolean
        get() = autoStartResult == RESULT_CANCELLED

    /** 是否为自动触发记录 */
    val isAutoTriggered: Boolean
        get() = triggerType == TRIGGER_AUTO
}
