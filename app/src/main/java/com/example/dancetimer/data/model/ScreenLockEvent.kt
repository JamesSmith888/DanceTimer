package com.example.dancetimer.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 锁屏/息屏事件记录
 *
 * 待机模式下每次检测到 ACTION_SCREEN_OFF 时写入一条记录。
 * 用于在空闲页展示最近锁屏时间列表，帮助用户回溯计时。
 *
 * @property timestamp  事件发生的墙钟时间 ([System.currentTimeMillis])
 * @property elapsedRealtime 事件发生时的 [android.os.SystemClock.elapsedRealtime]，
 *           用于精确计算到当前的已过时间（不受用户修改系统时间影响）
 */
@Entity(tableName = "screen_lock_events")
data class ScreenLockEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val elapsedRealtime: Long
)
