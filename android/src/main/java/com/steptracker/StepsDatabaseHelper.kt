package com.steptracker.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.database.Cursor
import com.facebook.react.bridge.*
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONArray
import org.json.JSONObject

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

import java.time.DayOfWeek
import java.util.Locale

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

    @ReactMethod
    fun getDailyHourlyStatsForDate(date: String): WritableMap {
        val db = readableDatabase

        val hourlyCursor = db.rawQuery(
            "SELECT hour, steps FROM hourly_history WHERE date = ?",
            arrayOf(date)
        )

        val resultArray = Arguments.createArray()
        var totalSteps = 0
        var mostSteps = 0
        var mostActiveHour = "00:00"
        var activeMinutes = 0

        val orderedHours = (6..23) + (0..5)
        for (hour in orderedHours) {
            val hourStr = String.format("%02d:00", hour)
            var steps = 0

            if (hourlyCursor.moveToFirst()) {
                do {
                    val dbHour = hourlyCursor.getInt(hourlyCursor.getColumnIndexOrThrow("hour"))
                    if (dbHour == hour) {
                        steps = hourlyCursor.getInt(hourlyCursor.getColumnIndexOrThrow("steps"))
                        break
                    }
                } while (hourlyCursor.moveToNext())
            }

            totalSteps += steps
            if (steps > mostSteps) {
                mostSteps = steps
                mostActiveHour = hourStr
            }
            if (steps > 0) activeMinutes += 60

            val distance = steps * 0.0008
            val calories = steps * 0.04

            val map = Arguments.createMap().apply {
                putString("hour", hourStr)
                putInt("steps", steps)
                putDouble("distance", distance)
                putDouble("calories", calories)
                putBoolean("active", steps > 0)
            }
            resultArray.pushMap(map)
        }

        hourlyCursor.close()

        val goalCursor = db.rawQuery(
            "SELECT goal FROM daily_history WHERE date = ?",
            arrayOf(date)
        )
        val goal = if (goalCursor.moveToFirst())
            goalCursor.getInt(goalCursor.getColumnIndexOrThrow("goal"))
        else 10000
        goalCursor.close()

        val prevCursor = db.rawQuery(
            "SELECT steps FROM daily_history WHERE date = date(?, '-1 day')",
            arrayOf(date)
        )
        val yesterdaySteps = if (prevCursor.moveToFirst())
            prevCursor.getInt(prevCursor.getColumnIndexOrThrow("steps"))
        else 0
        prevCursor.close()

        val totalDistance = totalSteps * 0.0008
        val totalCalories = totalSteps * 0.04
        val stepsPerMinuteAvg = if (activeMinutes > 0) totalSteps / activeMinutes else 0
        val goalCompleted = totalSteps >= goal
        val improvement = if (yesterdaySteps > 0)
            ((totalSteps - yesterdaySteps) * 100) / yesterdaySteps
        else 0

        return Arguments.createMap().apply {
            putArray("hours", resultArray)
            putInt("totalSteps", totalSteps)
            putDouble("totalDistance", totalDistance)
            putDouble("totalCalories", totalCalories)
            putInt("activeMinutes", activeMinutes)
            putInt("stepsPerMinuteAvg", stepsPerMinuteAvg)
            putInt("goal", goal)
            putBoolean("goalCompleted", goalCompleted)
            putString("mostActiveHour", mostActiveHour)
            putInt("improvement", improvement)
        }
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

    fun getWeeklyProgress(): WritableArray {
        val array = Arguments.createArray()
        val db = readableDatabase

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dayFormat = SimpleDateFormat("dd", Locale.getDefault())

        // Calcular fecha del lunes de esta semana
        val calendar = Calendar.getInstance(Locale.getDefault())
        calendar.firstDayOfWeek = Calendar.MONDAY
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)

        // Mapear las fechas de lunes a domingo (yyyy-MM-dd)
        val weekDates = (0..6).map {
            val dateStr = dateFormat.format(calendar.time)
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            dateStr
        }

        // Buscar pasos y objetivos para toda la semana
        val placeholders = weekDates.joinToString(",") { "?" }
        val cursor = db.rawQuery(
            """
            SELECT date, steps, goal FROM daily_history
            WHERE date IN ($placeholders)
            """.trimIndent(),
            weekDates.toTypedArray()
        )

        // Guardar los datos en un mapa para fácil acceso
        val stepsByDate = mutableMapOf<String, Pair<Int, Int>>() // date -> (steps, goal)
        while (cursor.moveToNext()) {
            val date = cursor.getString(cursor.getColumnIndexOrThrow("date"))
            val steps = cursor.getInt(cursor.getColumnIndexOrThrow("steps"))
            val goal = cursor.getInt(cursor.getColumnIndexOrThrow("goal"))
            stepsByDate[date] = steps to goal
        }
        cursor.close()

        // Construir el array en orden de lunes a domingo
        for (date in weekDates) {
            val (steps, goal) = stepsByDate[date] ?: 0 to 8000 // fallback goal
            val percentage = if (goal > 0) (steps * 100 / goal) else 0
            val completed = steps >= goal

            val map = Arguments.createMap().apply {
                putString("day", dayFormat.format(dateFormat.parse(date) ?: Date()))
                putInt("steps", steps)
                putInt("goal", goal)
                putBoolean("completed", completed)
                putInt("percentage", percentage)
            }
            array.pushMap(map)
        }

        return array
    }

    @ReactMethod
    fun getWeeklyStats(): WritableMap {
        val db = readableDatabase

        val today = LocalDate.now()
        val startOfWeek = today.minusDays(6)

        var totalSteps = 0
        var mostSteps = 0
        var mostActiveDay = ""
        var activeMinutes = 0
        val resultArray = Arguments.createArray()

        val orderedDays = (0..6).map { startOfWeek.plusDays(it.toLong()) }

        for (date in orderedDays) {
            val dateStr = date.format(DateTimeFormatter.ISO_DATE)
            val cursor = db.rawQuery("SELECT steps FROM daily_history WHERE date = ?", arrayOf(dateStr))

            val steps = if (cursor.moveToFirst()) cursor.getInt(0) else 0
            cursor.close()

            totalSteps += steps
            if (steps > mostSteps) {
                mostSteps = steps
                mostActiveDay = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("es", "ES"))
            }

            if (steps > 0) activeMinutes += 60

            val distance = steps * 0.0008
            val calories = steps * 0.04

            val dayName = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("es", "ES"))
            val isToday = date.isEqual(today)
            val dayNumber = date.dayOfMonth.toString()

            val map = Arguments.createMap().apply {
                putString("day", dayName.replaceFirstChar { it.uppercase() })
                putInt("steps", steps)
                putString("date", dayNumber)
                putBoolean("isToday", isToday)
                putDouble("distance", distance)
                putDouble("calories", calories)
                putInt("activeMinutes", if (steps > 0) 60 else 0)
            }

            resultArray.pushMap(map)
        }

        val goal = 35000
        val goalCompleted = totalSteps >= goal
        val totalDistance = totalSteps * 0.0008
        val totalCalories = totalSteps * 0.04
        val stepsPerMinuteAvg = if (activeMinutes > 0) totalSteps / activeMinutes else 0

        // Mejora respecto a semana pasada
        val prevStart = startOfWeek.minusDays(7)
        val prevEnd = startOfWeek.minusDays(1)
        val prevSteps = db.rawQuery(
            "SELECT SUM(steps) FROM daily_history WHERE date BETWEEN ? AND ?",
            arrayOf(prevStart.toString(), prevEnd.toString())
        ).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

        val improvement = if (prevSteps > 0) ((totalSteps - prevSteps) * 100) / prevSteps else 0

        return Arguments.createMap().apply {
            putInt("goal", goal)
            putInt("totalSteps", totalSteps)
            putDouble("totalDistance", totalDistance)
            putDouble("totalCalories", totalCalories)
            putInt("activeMinutes", activeMinutes)
            putInt("stepsPerMinuteAvg", stepsPerMinuteAvg)
            putBoolean("goalCompleted", goalCompleted)
            putString("mostActiveDay", mostActiveDay)
            putInt("improvement", improvement)
            putArray("days", resultArray)
        }
    }

    private fun roundTo1Decimal(value: Double): Double {
        return String.format("%.1f", value).replace(",", ".").toDouble()
    }

    private fun roundTo2Decimals(value: Double): Double {
        return String.format("%.2f", value).replace(",", ".").toDouble()
    }

    @ReactMethod
    fun getMonthlyStats(): WritableMap {
        val db = readableDatabase

        val today = LocalDate.now()
        val startOfMonth = today.withDayOfMonth(1)
        val endOfMonth = today.withDayOfMonth(today.lengthOfMonth())

        var totalSteps = 0
        var mostSteps = 0
        var mostActiveDay = ""
        var activeMinutes = 0
        val resultArray = Arguments.createArray()

        for (day in 1..today.lengthOfMonth()) {
            val date = startOfMonth.withDayOfMonth(day)
            val dateStr = date.format(DateTimeFormatter.ISO_DATE)
            val cursor = db.rawQuery("SELECT steps FROM daily_history WHERE date = ?", arrayOf(dateStr))

            val steps = if (cursor.moveToFirst()) cursor.getInt(0) else 0
            cursor.close()

            totalSteps += steps
            if (steps > mostSteps) {
                mostSteps = steps
                val dow = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("es", "ES"))
                mostActiveDay = "$dow ${day}"
            }

            if (steps > 0) activeMinutes += 60

            val distance = steps * 0.0008
            val calories = steps * 0.04
            val isToday = date.isEqual(today)

            val map = Arguments.createMap().apply {
                putString("day", day.toString())
                putInt("steps", steps)
                putBoolean("isToday", isToday)
                putDouble("distance", distance)
                putDouble("calories", calories)
                putInt("activeMinutes", if (steps > 0) 60 else 0)
            }

            resultArray.pushMap(map)
        }

        val goal = 150000
        val goalCompleted = totalSteps >= goal
        val totalDistance = totalSteps * 0.0008
        val totalCalories = totalSteps * 0.04
        val stepsPerMinuteAvg = if (activeMinutes > 0) totalSteps / activeMinutes else 0

        // Mejora respecto al mes pasado
        val prevMonthStart = startOfMonth.minusMonths(1).withDayOfMonth(1)
        val prevMonthEnd = prevMonthStart.withDayOfMonth(prevMonthStart.lengthOfMonth())
        val prevSteps = db.rawQuery(
            "SELECT SUM(steps) FROM daily_history WHERE date BETWEEN ? AND ?",
            arrayOf(prevMonthStart.toString(), prevMonthEnd.toString())
        ).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

        val improvement = if (prevSteps > 0) ((totalSteps - prevSteps) * 100) / prevSteps else 0

        return Arguments.createMap().apply {
            putInt("goal", goal)
            putInt("totalSteps", totalSteps)
            putDouble("totalDistance", totalDistance)
            putDouble("totalCalories", totalCalories)
            putInt("activeMinutes", activeMinutes)
            putInt("stepsPerMinuteAvg", stepsPerMinuteAvg)
            putBoolean("goalCompleted", goalCompleted)
            putString("mostActiveDay", mostActiveDay)
            putInt("improvement", improvement)
            putArray("days", resultArray)
        }
    }


    fun getYearlyStats(): WritableMap {
        val db = readableDatabase
        val today = LocalDate.now()
        val year = today.year

        var totalSteps = 0
        var mostSteps = 0
        var mostActiveMonth = ""
        var activeMinutes = 0
        val monthsArray = Arguments.createArray()

        val shortMonths = listOf(
            "Ene", "Feb", "Mar", "Abr", "May", "Jun",
            "Jul", "Ago", "Sep", "Oct", "Nov", "Dic"
        )
        val fullMonths = listOf(
            "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
            "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
        )

        for (month in 1..12) {
            val startOfMonth = LocalDate.of(year, month, 1)
            val endOfMonth = startOfMonth.withDayOfMonth(startOfMonth.lengthOfMonth())

            val cursor = db.rawQuery(
                "SELECT SUM(steps) FROM daily_history WHERE date BETWEEN ? AND ?",
                arrayOf(startOfMonth.toString(), endOfMonth.toString())
            )

            val steps = if (cursor.moveToFirst()) cursor.getInt(0) else 0
            cursor.close()

            totalSteps += steps
            if (steps > mostSteps) {
                mostSteps = steps
                mostActiveMonth = fullMonths[month - 1]
            }
            if (steps > 0) activeMinutes += startOfMonth.lengthOfMonth() * 60

            val distance = steps * 0.0008
            val calories = steps * 0.04

            val monthMap = Arguments.createMap().apply {
                putString("month", shortMonths[month - 1])
                putString("fullMonth", fullMonths[month - 1])
                putInt("steps", steps)
                putDouble("distance", distance)
                putDouble("calories", calories)
            }

            monthsArray.pushMap(monthMap)
        }

        val goal = 1800000
        val goalCompleted = totalSteps >= goal
        val totalDistance = totalSteps * 0.0008
        val totalCalories = totalSteps * 0.04
        val stepsPerMinuteAvg = if (activeMinutes > 0) totalSteps / activeMinutes else 0

        // Mejora respecto al año anterior
        val lastYearStart = LocalDate.of(year - 1, 1, 1)
        val lastYearEnd = LocalDate.of(year - 1, 12, 31)
        val prevSteps = db.rawQuery(
            "SELECT SUM(steps) FROM daily_history WHERE date BETWEEN ? AND ?",
            arrayOf(lastYearStart.toString(), lastYearEnd.toString())
        ).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

        val improvement = if (prevSteps > 0) ((totalSteps - prevSteps) * 100) / prevSteps else 0

        return Arguments.createMap().apply {
            putInt("goal", goal)
            putInt("totalSteps", totalSteps)
            putDouble("totalDistance", totalDistance)
            putDouble("totalCalories", totalCalories)
            putInt("activeMinutes", activeMinutes)
            putInt("stepsPerMinuteAvg", stepsPerMinuteAvg)
            putBoolean("goalCompleted", goalCompleted)
            putString("mostActiveMonth", mostActiveMonth)
            putInt("improvement", improvement)
            putArray("months", monthsArray)
        }
    }

    fun setUserLanguage(lang: String) {
        setConfigValue("user_language", lang)
    }

    fun getUserLanguage(): String {
        return getConfigValue("user_language") ?: "es"
    }
}
