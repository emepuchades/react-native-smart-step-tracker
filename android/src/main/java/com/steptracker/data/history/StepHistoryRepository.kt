package com.steptracker.data.steps

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.text.SimpleDateFormat
import java.util.*

class StepHistoryRepository(
    private val db: SQLiteDatabase,
    private val config: ConfigRepository
) {

    fun insertOrUpdateDaily(date: String, steps: Int, offset: Int, goal: Int) {
        val values = ContentValues().apply {
            put("date", date)
            put("steps", steps)
            put("offset", offset)
            put("goal", goal)
        }
        db.insertWithOnConflict(
            "daily_history",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getStepsForDate(date: String): Int {
        val cursor = db.rawQuery(
            "SELECT steps FROM daily_history WHERE date = ?",
            arrayOf(date)
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun setStepsForDate(date: String, steps: Int) {
        val offset = getTodayOffset(date)
        val goal = getDailyGoal()
        insertOrUpdateDaily(date, steps, offset, goal)
    }

    fun getTodayOffset(date: String): Int {
        val cursor = db.rawQuery(
            "SELECT offset FROM daily_history WHERE date = ?",
            arrayOf(date)
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else -1 }
    }

    fun setTodayOffset(date: String, offset: Int) {
        val steps = getStepsForDate(date)
        val goal = getDailyGoal()
        insertOrUpdateDaily(date, steps, offset, goal)
    }

    // GOAL
    fun getDailyGoal(): Int {
        return config.get("daily_goal")?.toIntOrNull() ?: 10000
    }

    fun setDailyGoal(goal: Int) {
        config.set("daily_goal", goal.toString())

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val values = ContentValues().apply { put("goal", goal) }

        db.update("daily_history", values, "date = ?", arrayOf(today))
    }

    // HOURLY HISTORY
    fun insertOrUpdateHourly(date: String, hour: Int, steps: Int) {

        val cursor = db.rawQuery(
            "SELECT steps FROM hourly_history WHERE date = ? AND hour = ?",
            arrayOf(date, hour.toString())
        )

        var exists = false
        var existingSteps = 0

        if (cursor.moveToFirst()) {
            exists = true
            existingSteps = cursor.getInt(0)
        }
        cursor.close()

        if (exists) {
            val values = ContentValues().apply {
                put("steps", existingSteps + steps)
            }
            db.update(
                "hourly_history",
                values,
                "date = ? AND hour = ?",
                arrayOf(date, hour.toString())
            )

        } else {
            val values = ContentValues().apply {
                put("date", date)
                put("hour", hour)
                put("steps", steps)
            }
            db.insert("hourly_history", null, values)
        }
    }
}
