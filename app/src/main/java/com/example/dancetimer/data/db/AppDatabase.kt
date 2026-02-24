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

@Database(
    entities = [PricingRule::class, PriceTier::class, DanceRecord::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pricingRuleDao(): PricingRuleDao
    abstract fun danceRecordDao(): DanceRecordDao

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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dance_timer.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
