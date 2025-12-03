package com.steptracker.data.steps

import android.database.sqlite.SQLiteDatabase
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import java.time.LocalDate
import java.time.Year
import java.time.format.TextStyle
import java.util.*

class StepStatsRepository(
    private val db: SQLiteDatabase,
    private val prefs: UserPreferencesManager,
    private val history: StepHistoryRepository
) {

    private val dailyGoal get() = history.getDailyGoal()
    private val locale get() = Locale(prefs.getLanguage(), "")

    fun getDailyHourlyStats(date: String): WritableMap {
        val cursor = db.rawQuery(
            "SELECT hour, steps FROM hourly_history WHERE date = ?",
            arrayOf(date)
        )

        val stepsByHour = mutableMapOf<Int, Int>()
        if (cursor.moveToFirst()) {
            do {
                stepsByHour[cursor.getInt(0)] = cursor.getInt(1)
            } while (cursor.moveToNext())
        }
        cursor.close()

        val orderedHours = (6..23) + (0..5)
        val hoursArray = Arguments.createArray()

        var totalSteps = 0
        var mostSteps = 0
        var mostActiveHour = "00:00"
        var activeMinutes = 0

        for (hour in orderedHours) {
            val steps = stepsByHour[hour] ?: 0
            val hourStr = "%02d:00".format(hour)

            if (steps > 0) activeMinutes += 60
            if (steps > mostSteps) {
                mostSteps = steps
                mostActiveHour = hourStr
            }

            totalSteps += steps

            hoursArray.pushMap(
                Arguments.createMap().apply {
                    putString("hour", hourStr)
                    putInt("steps", steps)
                    putDouble("distance", calculateDistance(steps))
                    putDouble("calories", calculateCalories(steps))
                    putBoolean("active", steps > 0)
                }
            )
        }

        val yesterdaySteps = getSteps(date, -1)
        val totalDistance = calculateDistance(totalSteps)

        val yesterdayDistance = calculateDistance(yesterdaySteps)
        val distanceDiffPercent =
            if (yesterdayDistance > 0) ((totalDistance - yesterdayDistance) / yesterdayDistance) * 100 else 0.0

        return Arguments.createMap().apply {
            putArray("hours", hoursArray)
            putInt("totalSteps", totalSteps)
            putDouble("totalDistance", totalDistance)
            putDouble("totalCalories", calculateCalories(totalSteps))
            putDouble("distanceDiffPercent", distanceDiffPercent)
            putInt("activeMinutes", activeMinutes)
            putInt("stepsPerMinuteAvg", if (activeMinutes > 0) totalSteps / activeMinutes else 0)
            putInt("goal", dailyGoal)
            putBoolean("goalCompleted", totalSteps >= dailyGoal)
            putString("mostActiveHour", mostActiveHour)
        }
    }

    fun getWeeklyStats(offset: Int): WritableMap {
        val weekStart = prefs.getWeekStart()
        val today = LocalDate.now()
        val shift = computeWeekStartShift(today, weekStart)

        val start = today.minusDays(shift.toLong()).plusWeeks(offset.toLong())
        val end = start.plusDays(6)

        var totalSteps = 0
        var mostSteps = 0
        var leastSteps = Int.MAX_VALUE
        var mostActiveDay = ""
        var leastActiveDay = ""
        var activeMinutes = 0
        var goalDays = 0

        val daysArray = Arguments.createArray()

        for (i in 0..6) {
            val date = start.plusDays(i.toLong())
            val steps = getSteps(date.toString())

            totalSteps += steps
            if (steps > 0) activeMinutes += 60
            if (steps > mostSteps) {
                mostSteps = steps
                mostActiveDay = dayFull(date)
            }
            if (steps < leastSteps) {
                leastSteps = steps
                leastActiveDay = dayFull(date)
            }
            if (steps >= dailyGoal) goalDays++

            daysArray.pushMap(
                Arguments.createMap().apply {
                    putString("day", dayShort(date))
                    putString("date", date.dayOfMonth.toString())
                    putInt("steps", steps)
                    putBoolean("isToday", date == today)
                    putDouble("distance", calculateDistance(steps))
                    putDouble("calories", calculateCalories(steps))
                    putBoolean("goalCompleted", steps >= dailyGoal)
                }
            )
        }

        val prevSteps = getStepsRange(start.minusWeeks(1), start.minusWeeks(1).plusDays(6))
        val diff = totalSteps - prevSteps
        val improvement = if (prevSteps > 0) diff.toDouble() / prevSteps * 100 else 0.0

        return Arguments.createMap().apply {
            putInt("goal", dailyGoal * 7)
            putInt("totalSteps", totalSteps)
            putDouble("totalDistance", calculateDistance(totalSteps))
            putDouble("totalCalories", calculateCalories(totalSteps))
            putString("mostActiveDay", mostActiveDay)
            putString("leastActiveDay", leastActiveDay)
            putInt("daysGoalCompleted", goalDays)
            putInt("activeMinutes", activeMinutes)
            putArray("days", daysArray)
            putDouble("improvement", improvement)
            putInt("stepDifference", diff)
            putString("startDate", start.toString())
            putString("endDate", end.toString())
        }
    }

    fun getMonthlyStats(offset: Int): WritableMap {
        val today = LocalDate.now()
        val target = today.plusMonths(offset.toLong())
        val days = target.lengthOfMonth()

        val array = Arguments.createArray()

        var totalSteps = 0
        var mostSteps = 0
        var leastSteps = Int.MAX_VALUE
        var mostActiveDay = ""
        var leastActiveDay = ""
        var activeMinutes = 0
        var goalDays = 0

        for (d in 1..days) {
            val date = target.withDayOfMonth(d)
            val steps = getSteps(date.toString())

            totalSteps += steps
            if (steps > 0) activeMinutes += 60
            if (steps >= dailyGoal) goalDays++
            if (steps > mostSteps) {
                mostSteps = steps
                mostActiveDay = dayFull(date)
            }
            if (steps < leastSteps) {
                leastSteps = steps
                leastActiveDay = dayFull(date)
            }

            array.pushMap(
                Arguments.createMap().apply {
                    putString("day", d.toString())
                    putInt("steps", steps)
                    putBoolean("isToday", date == today)
                    putDouble("distance", calculateDistance(steps))
                    putDouble("calories", calculateCalories(steps))
                }
            )
        }

        val prevStart = target.minusMonths(1).withDayOfMonth(1)
        val prevEnd = prevStart.withDayOfMonth(prevStart.lengthOfMonth())
        val prevSteps = getStepsRange(prevStart, prevEnd)

        val diff = totalSteps - prevSteps
        val improvement = if (prevSteps > 0) diff.toDouble() / prevSteps * 100 else 0.0

        return Arguments.createMap().apply {
            putInt("goal", dailyGoal * 30)
            putInt("totalSteps", totalSteps)
            putDouble("totalDistance", calculateDistance(totalSteps))
            putDouble("totalCalories", calculateCalories(totalSteps))
            putInt("activeMinutes", activeMinutes)
            putString("mostActiveDay", mostActiveDay)
            putString("leastActiveDay", leastActiveDay)
            putInt("daysGoalCompleted", goalDays)
            putArray("days", array)
            putDouble("improvement", improvement)
            putInt("stepDifference", diff)
        }
    }

    fun getYearlyStats(offset: Int): WritableMap {
        val year = LocalDate.now().year + offset

        var totalSteps = 0
        var mostSteps = 0
        var leastSteps = Int.MAX_VALUE
        var mostActive = ""
        var leastActive = ""

        val monthsArray = Arguments.createArray()

        for (m in 1..12) {
            val start = LocalDate.of(year, m, 1)
            val end = start.withDayOfMonth(start.lengthOfMonth())

            val steps = getStepsRange(start, end)
            totalSteps += steps

            if (steps > mostSteps) {
                mostSteps = steps
                mostActive = start.month.getDisplayName(TextStyle.FULL, locale)
            }

            if (steps < leastSteps) {
                leastSteps = steps
                leastActive = start.month.getDisplayName(TextStyle.FULL, locale)
            }

            monthsArray.pushMap(
                Arguments.createMap().apply {
                    putString("month", start.month.getDisplayName(TextStyle.SHORT, locale))
                    putInt("steps", steps)
                    putDouble("distance", calculateDistance(steps))
                    putDouble("calories", calculateCalories(steps))
                }
            )
        }

        return Arguments.createMap().apply {
            putInt("goal", dailyGoal * 365)
            putInt("totalSteps", totalSteps)
            putDouble("totalDistance", calculateDistance(totalSteps))
            putDouble("totalCalories", calculateCalories(totalSteps))
            putString("mostActiveMonth", mostActive)
            putString("leastActiveMonth", leastActive)
            putArray("months", monthsArray)
        }
    }

    private fun dayShort(d: LocalDate) =
        d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)

    private fun dayFull(d: LocalDate) =
        d.dayOfWeek.getDisplayName(TextStyle.FULL, locale)

    private fun calculateDistance(steps: Int): Double =
        if (prefs.getDistanceUnit() == "km") steps * 0.0008
        else (steps * 0.0008) / 1.60934

    private fun calculateCalories(steps: Int): Double =
        if (prefs.getEnergyUnit() == "kcal") steps * 0.04
        else (steps * 0.04) * 4.184

    private fun getSteps(date: String): Int =
        db.rawQuery(
            "SELECT steps FROM daily_history WHERE date = ?",
            arrayOf(date)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun getSteps(date: String, offset: Int): Int =
        db.rawQuery(
            "SELECT steps FROM daily_history WHERE date = date(?, ? || ' day')",
            arrayOf(date, offset.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun getStepsRange(start: LocalDate, end: LocalDate): Int =
        db.rawQuery(
            "SELECT SUM(steps) FROM daily_history WHERE date BETWEEN ? AND ?",
            arrayOf(start.toString(), end.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun computeWeekStartShift(today: LocalDate, start: String): Int =
        when (start) {
            "sunday" -> if (today.dayOfWeek.value == 7) 0 else today.dayOfWeek.value
            "monday" -> today.dayOfWeek.value - 1
            else -> 0
        }
}
