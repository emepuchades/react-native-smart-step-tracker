package com.steptracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "daily_summary", indices = [Index("date", unique = true)])
data class DailySummary(
    @PrimaryKey val date: String,   // "2025-06-12"
    val steps: Int = 0,
)