package com.example.dancetimer.service

import com.example.dancetimer.data.model.PriceTier

/**
 * 计时器状态 — 被 Service/UI/Widget 共享
 */
sealed class TimerState {
    data object Idle : TimerState()

    data class Running(
        val elapsedSeconds: Int = 0,
        val currentSongIndex: Int = 0,
        val cost: Float = 0f,
        val songCount: Int = 0,
        val startTimeMillis: Long = 0L,
        val tiers: List<PriceTier> = emptyList(),
        val ruleName: String = "",
        val ruleId: Long = 0L,
        val isPaused: Boolean = false,
        val isInGracePeriod: Boolean = false,
        val isAutoStarted: Boolean = false
    ) : TimerState()

    data class Finished(
        val durationSeconds: Int,
        val cost: Float,
        val songCount: Int,
        val ruleName: String,
        val ruleId: Long,
        val startTimeMillis: Long,
        val endTimeMillis: Long,
        val isGraceApplied: Boolean = false,
        val savedAmount: Float = 0f
    ) : TimerState()
}
