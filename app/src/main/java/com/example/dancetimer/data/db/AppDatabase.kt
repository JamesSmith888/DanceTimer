package com.example.dancetimer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.dancetimer.data.model.DanceRecord
import com.example.dancetimer.data.model.PriceTier
import com.example.dancetimer.data.model.PricingRule

@Database(
    entities = [PricingRule::class, PriceTier::class, DanceRecord::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pricingRuleDao(): PricingRuleDao
    abstract fun danceRecordDao(): DanceRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dance_timer.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
