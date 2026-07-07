package com.example.dancetimer.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent

/**
 * 音量键手势检测器 — 长按模式
 * 由 Activity 共用
 */
class VolumeKeyDetector(
    private val onVolumeUpTriggered: () -> Unit,
    private val onVolumeDownTriggered: () -> Unit
) {
    // ---- 长按检测 ----
    private var volumeUpDownTime: Long = 0L
    private var volumeDownDownTime: Long = 0L
    private var volumeUpTriggered = false
    private var volumeDownTriggered = false
    private val handler = Handler(Looper.getMainLooper())

    private var volumeUpLongPressRunnable: Runnable? = null
    private var volumeDownLongPressRunnable: Runnable? = null

    private companion object {
        private const val TAG = "VolumeKeyDetector"
        const val LONG_PRESS_THRESHOLD_MS = 1500L
    }

    /**
     * 处理按键事件
     * @return true 表示已消费该事件（不传递给系统）
     */
    fun onKeyEvent(keyCode: Int, action: Int): Boolean {
        return handleLongPress(keyCode, action)
    }

    // ---------- 长按模式 ----------

    private fun handleLongPress(keyCode: Int, action: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    if (volumeUpDownTime == 0L) {
                        volumeUpDownTime = System.currentTimeMillis()
                        volumeUpTriggered = false
                        Log.d(TAG, "音量+ 按下，启动${LONG_PRESS_THRESHOLD_MS}ms延迟触发")

                        volumeUpLongPressRunnable = Runnable {
                            if (!volumeUpTriggered && volumeUpDownTime > 0) {
                                volumeUpTriggered = true
                                Log.d(TAG, "音量+ 长按时间到，触发！")
                                onVolumeUpTriggered()
                            }
                        }
                        handler.postDelayed(volumeUpLongPressRunnable!!, LONG_PRESS_THRESHOLD_MS)
                    }
                    return true
                } else if (action == KeyEvent.ACTION_UP) {
                    val heldTime = if (volumeUpDownTime > 0) System.currentTimeMillis() - volumeUpDownTime else 0
                    Log.d(TAG, "音量+ 松开，持续${heldTime}ms，已触发=$volumeUpTriggered")
                    volumeUpLongPressRunnable?.let { handler.removeCallbacks(it) }
                    volumeUpDownTime = 0L
                    return true
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    if (volumeDownDownTime == 0L) {
                        volumeDownDownTime = System.currentTimeMillis()
                        volumeDownTriggered = false
                        Log.d(TAG, "音量- 按下，启动${LONG_PRESS_THRESHOLD_MS}ms延迟触发")

                        volumeDownLongPressRunnable = Runnable {
                            if (!volumeDownTriggered && volumeDownDownTime > 0) {
                                volumeDownTriggered = true
                                Log.d(TAG, "音量- 长按时间到，触发！")
                                onVolumeDownTriggered()
                            }
                        }
                        handler.postDelayed(volumeDownLongPressRunnable!!, LONG_PRESS_THRESHOLD_MS)
                    }
                    return true
                } else if (action == KeyEvent.ACTION_UP) {
                    val heldTime = if (volumeDownDownTime > 0) System.currentTimeMillis() - volumeDownDownTime else 0
                    Log.d(TAG, "音量- 松开，持续${heldTime}ms，已触发=$volumeDownTriggered")
                    volumeDownLongPressRunnable?.let { handler.removeCallbacks(it) }
                    volumeDownDownTime = 0L
                    return true
                }
            }
        }
        return false
    }

    /** 重置所有状态 */
    fun reset() {
        volumeUpLongPressRunnable?.let { handler.removeCallbacks(it) }
        volumeDownLongPressRunnable?.let { handler.removeCallbacks(it) }
        volumeUpDownTime = 0L
        volumeDownDownTime = 0L
        volumeUpTriggered = false
        volumeDownTriggered = false
    }
}

