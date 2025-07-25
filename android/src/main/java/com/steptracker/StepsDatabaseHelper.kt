package com.steptracker.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.database.Cursor
import java.text.SimpleDateFormat
import java.util.*

class StepsDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        const val DATABASE_NAME = "steps.db"
        const val DATABASE_VERSION = 2
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS daily_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT UNIQUE,
                steps INTEGER,
                offset INTEGER,
                goal INTEGER DEFAULT 10000
            );
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS hourly_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT,
                hour INTEGER,
                steps INTEGER
            );
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS config (
                key TEXT PRIMARY KEY,
                value TEXT
            );
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE daily_history ADD COLUMN goal INTEGER DEFAULT 10000;")
        }
    }

    fun setConfigValue(key: String, value: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        db.insertWithOnConflict(
            "config",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getConfigValue(key: String): String? {
        val db = readableDatabase
        val cursor: Cursor = db.rawQuery(
            "SELECT value FROM config WHERE key = ?",
            arrayOf(key)
        )
        return if (cursor.moveToFirst()) {
            val value = cursor.getString(0)
            cursor.close()
            value
        } else {
            cursor.close()
            null
        }
    }

    fun getLastCounter(): Float {
        return getConfigValue("last_counter")?.toFloatOrNull() ?: -1f
    }

    fun setLastCounter(value: Float) {
        setConfigValue("last_counter", value.toString())
    }

    fun getPrevDate(): String? {
        return getConfigValue("prev_date")
    }

    fun setPrevDate(date: String) {
        setConfigValue("prev_date", date)
    }

    fun getLastCounterTime(): Long {
        return getConfigValue("last_counter_time")?.toLongOrNull() ?: -1L
    }

    fun setLastCounterTime(value: Long) {
        setConfigValue("last_counter_time", value.toString())
    }

    fun getDailyGoal(): Int {
        return getConfigValue("daily_goal")?.toIntOrNull() ?: 10000
    }

    fun setDailyGoal(goal: Int) {
        setConfigValue("daily_goal", goal.toString())

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val values = ContentValues().apply {
            put("goal", goal)
        }

        writableDatabase.update(
            "daily_history",
            values,
            "date = ?",
            arrayOf(today)
        )
    }

    fun insertOrUpdateDaily(date: String, steps: Int, offset: Int, goal: Int) {
        val db = writableDatabase
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
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT steps FROM daily_history WHERE date = ?", arrayOf(date))
        return if (cursor.moveToFirst()) {
            val result = cursor.getInt(0)
            cursor.close()
            result
        } else {
            cursor.close()
            0
        }
    }

    fun setStepsForDate(date: String, steps: Float) {
        val offset = getTodayOffset(date)
        val goal = getDailyGoal()
        insertOrUpdateDaily(date, steps.toInt(), offset.toInt(), goal)
    }

    fun getTodayOffset(date: String): Float {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT offset FROM daily_history WHERE date = ?",
            arrayOf(date)
        )
        return if (cursor.moveToFirst()) {
            val offset = cursor.getInt(0)
            cursor.close()
            offset.toFloat()
        } else {
            cursor.close()
            -1f
        }
    }

    fun setTodayOffset(date: String, offset: Float) {
        val steps = getStepsForDate(date)
        val goal = getDailyGoal()
        insertOrUpdateDaily(date, steps.toInt(), offset.toInt(), goal)
    }

    fun insertOrUpdateHourly(date: String, hour: Int, steps: Int) {
        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT steps FROM hourly_history WHERE date = ? AND hour = ?",
            arrayOf(date, hour.toString())
        )

        if (cursor.moveToFirst()) {
            val existing = cursor.getInt(0)
            cursor.close()

            val newTotal = existing + steps
            val values = ContentValues().apply {
                put("steps", newTotal)
            }
            db.update(
                "hourly_history",
                values,
                "date = ? AND hour = ?",
                arrayOf(date, hour.toString())
            )
        } else {
            cursor.close()

            val values = ContentValues().apply {
                put("date", date)
                put("hour", hour)
                put("steps", steps)
            }
            db.insert("hourly_history", null, values)
        }
    }

    fun getAllDailyHistory(): Cursor {
        val db = readableDatabase
        return db.rawQuery("SELECT date, steps, offset FROM daily_history", null)
    }

    fun getHourlyForDate(date: String): Cursor {
        val db = readableDatabase
        return db.rawQuery(
            "SELECT hour, steps FROM hourly_history WHERE date = ?",
            arrayOf(date)
        )
    }

    fun getWeeklySummary(): Map<String, Any> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT date, steps FROM daily_history
            ORDER BY date DESC
            LIMIT 7
            """.trimIndent(), null
        )

        var totalSteps = 0
        var activeDays = 0
        var daysCount = 0

        while (cursor.moveToNext()) {
            daysCount += 1
            val steps = cursor.getInt(cursor.getColumnIndexOrThrow("steps"))
            totalSteps += steps
            if (steps > 0) activeDays += 1
        }
        cursor.close()

        val averageSteps = if (activeDays > 0) totalSteps / activeDays else 0

        return mapOf(
            "totalSteps" to totalSteps,
            "averageSteps" to averageSteps,
            "activeDays" to activeDays,
            "daysCount" to daysCount
        )
    }

    fun getStreakCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT date, steps, goal FROM daily_history ORDER BY date DESC",
            null
        )

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        var streak = 0
        while (cursor.moveToNext()) {
            val date = cursor.getString(cursor.getColumnIndexOrThrow("date"))

            if (date == today) {
                continue
            }

            val steps = cursor.getInt(cursor.getColumnIndexOrThrow("steps"))
            val goal = cursor.getInt(cursor.getColumnIndexOrThrow("goal"))

            if (steps >= goal) {
                streak += 1
            } else {
                break
            }
        }
        cursor.close()
        return streak
    }

    fun setUserLanguage(lang: String) {
        setConfigValue("user_language", lang)
    }

    fun getUserLanguage(): String {
        return getConfigValue("user_language") ?: "es"
    }
}
