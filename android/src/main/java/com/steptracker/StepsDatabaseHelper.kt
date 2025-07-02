package com.steptracker.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.database.Cursor

class StepsDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        const val DATABASE_NAME = "steps.db"
        const val DATABASE_VERSION = 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS daily_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT UNIQUE,
                steps INTEGER,
                offset INTEGER
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
        db.execSQL("DROP TABLE IF EXISTS daily_history")
        db.execSQL("DROP TABLE IF EXISTS hourly_history")
        db.execSQL("DROP TABLE IF EXISTS config")
        onCreate(db)
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

    fun insertOrUpdateDaily(date: String, steps: Int, offset: Int) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("date", date)
            put("steps", steps)
            put("offset", offset)
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
        insertOrUpdateDaily(date, steps.toInt(), offset.toInt())
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
        insertOrUpdateDaily(date, steps.toInt(), offset.toInt())
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

        while (cursor.moveToNext()) {
            val steps = cursor.getInt(cursor.getColumnIndexOrThrow("steps"))
            totalSteps += steps
            if (steps > 0) activeDays += 1
        }
        cursor.close()

        val averageSteps = if (activeDays > 0) totalSteps / activeDays else 0

        return mapOf(
            "totalSteps" to totalSteps,
            "averageSteps" to averageSteps,
            "activeDays" to activeDays
        )
    }

}
