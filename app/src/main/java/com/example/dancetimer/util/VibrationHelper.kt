package com.example.dancetimer.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 震动反馈助手 — 兼容 API 24-35
 */
object VibrationHelper {

    /** 统一的震动时长（适中偏短）*/
    private const val FEEDBACK_DURATION_MS = 120L

    private fun getVibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /**
     * 统一震动反馈 — 适中偏短 (120ms)
     * 所有场景（启动、停止、暂停、恢复、档位到达等）均使用同一震感
     */
    fun vibrateFeedback(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(FEEDBACK_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(FEEDBACK_DURATION_MS)
        }
    }

    /** 轻触反馈 — 同样使用统一震动 */
    fun vibrateTick(context: Context) {
        vibrateFeedback(context)
    }
}
