package com.example.dancetimer.data.db

import androidx.room.*
import com.example.dancetimer.data.model.ScreenLockEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface ScreenLockEventDao {

    @Insert
    suspend fun insert(event: ScreenLockEvent)

    /**
     * 获取指定时间戳之后的所有锁屏事件（按时间降序）
     * @param sinceTimestamp 起始墙钟时间 ([System.currentTimeMillis] 基准)
     */
    @Query("SELECT * FROM screen_lock_events WHERE timestamp >= :sinceTimestamp ORDER BY timestamp DESC")
    fun getRecentEvents(sinceTimestamp: Long): Flow<List<ScreenLockEvent>>

    /** 获取全部锁屏事件（按时间降序） */
    @Query("SELECT * FROM screen_lock_events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<ScreenLockEvent>>

    /** 分页获取锁屏事件（按时间降序） */
    @Query("SELECT * FROM screen_lock_events ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getEventsPaged(limit: Int, offset: Int): List<ScreenLockEvent>

    /** 获取锁屏事件总数 */
    @Query("SELECT COUNT(*) FROM screen_lock_events")
    fun getTotalEventCount(): Flow<Int>

    /** 获取最近一条锁屏事件 */
    @Query("SELECT * FROM screen_lock_events ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestEvent(): ScreenLockEvent?

    /** 删除单条记录 */
    @Query("DELETE FROM screen_lock_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * 清理过期数据 — 删除指定时间戳之前的记录
     * @param beforeTimestamp 截止墙钟时间
     */
    @Query("DELETE FROM screen_lock_events WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)

    @Query("DELETE FROM screen_lock_events")
    suspend fun deleteAll()
}
