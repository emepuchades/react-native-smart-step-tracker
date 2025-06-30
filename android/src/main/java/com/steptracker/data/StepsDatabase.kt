package com.steptracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [DailySummary::class, HourlyBreakdown::class], version = 1)
abstract class StepsDatabase : RoomDatabase() {
    abstract fun dao(): StepsDao

    companion object {
        @Volatile private var INSTANCE: StepsDatabase? = null
        fun get(ctx: Context): StepsDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(ctx, StepsDatabase::class.java, "steps.db").build().also {
                INSTANCE = it
            }
        }
    }
}