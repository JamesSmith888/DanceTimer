package com.example.dancetimer.data.db

import androidx.room.*
import com.example.dancetimer.data.model.DanceRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface DanceRecordDao {

    @Insert
    suspend fun insert(record: DanceRecord): Long

    @Delete
    suspend fun delete(record: DanceRecord)

    @Query("DELETE FROM dance_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM dance_records ORDER BY startTime DESC")
    fun getAll(): Flow<List<DanceRecord>>

    @Query("SELECT * FROM dance_records WHERE id = :id")
    suspend fun getById(id: Long): DanceRecord?

    @Query("SELECT * FROM dance_records WHERE startTime >= :startOfDay AND startTime < :endOfDay ORDER BY startTime DESC")
    fun getByDateRange(startOfDay: Long, endOfDay: Long): Flow<List<DanceRecord>>

    /** 指定时间范围的总费用（左闭右开区间：[from, to)） */
    @Query("SELECT COALESCE(SUM(cost), 0) FROM dance_records WHERE startTime >= :from AND startTime < :to")
    fun getCostInRange(from: Long, to: Long): Flow<Float>

    /** 全部记录累计费用 */
    @Query("SELECT COALESCE(SUM(cost), 0) FROM dance_records")
    fun getTotalCost(): Flow<Float>

    /** 总记录数 */
    @Query("SELECT COUNT(*) FROM dance_records")
    fun getTotalCount(): Flow<Int>

    @Query("DELETE FROM dance_records")
    suspend fun deleteAll()
}
