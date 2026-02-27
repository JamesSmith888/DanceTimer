package com.example.dancetimer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.dancetimer.data.model.DanceRecord
import com.example.dancetimer.data.model.PriceTier
import com.example.dancetimer.data.model.PricingRule
import com.example.dancetimer.data.model.ScreenLockEvent

@Database(
    entities = [PricingRule::class, PriceTier::class, DanceRecord::class, ScreenLockEvent::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pricingRuleDao(): PricingRuleDao
    abstract fun danceRecordDao(): DanceRecordDao
    abstract fun screenLockEventDao(): ScreenLockEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 → v2：DanceRecord 新增自动计时元数据字段。
         * SQLite ALTER TABLE ADD COLUMN 对已有行使用 DEFAULT 值填充，历史数据不丢失。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dance_records ADD COLUMN triggerType TEXT NOT NULL DEFAULT 'manual'")
                db.execSQL("ALTER TABLE dance_records ADD COLUMN autoStartResult TEXT")
                db.execSQL("ALTER TABLE dance_records ADD COLUMN cancelledDurationSeconds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE dance_records ADD COLUMN screenOffDelaySeconds INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v2 → v3：新增 screen_lock_events 表，存储锁屏事件用于回溯计时。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS screen_lock_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        elapsedRealtime INTEGER NOT NULL
                    )"""
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dance_timer.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
