package com.steptracker.data

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "hourly_breakdown",
    primaryKeys = ["date", "hour"],
    indices = [Index("date"), Index("hour")]
)
data class HourlyBreakdown(
    val date: String,
    val hour: Int,
    val steps: Int = 0,
)