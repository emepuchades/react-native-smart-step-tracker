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
            if (yesterdayDistance > 0)
                ((totalDistance - yesterdayDistance) / yesterdayDistance) * 100
            else 0.0

        val distanceUnit = prefs.getDistanceUnit()
        val energyUnit = prefs.getEnergyUnit()

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
            putString("distanceUnit", distanceUnit)
            putString("energyUnit", energyUnit)
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

        var minSteps = Int.MAX_VALUE
        var maxSteps = 0

        val daysArray = Arguments.createArray()

        val lastValidIndex = when {
            today.isBefore(start) -> -1
            today.isAfter(end)    -> 6
            else -> java.time.temporal.ChronoUnit.DAYS.between(start, today).toInt()
        }

        for (i in 0..6) {
            val date = start.plusDays(i.toLong())
            val isFutureDay = i > lastValidIndex
            val steps = if (isFutureDay) 0 else getSteps(date.toString())

            if (!isFutureDay) {
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

                if (steps > maxSteps) maxSteps = steps
                if (steps < minSteps) minSteps = steps
                if (steps >= dailyGoal) goalDays++
            }

            daysArray.pushMap(
                Arguments.createMap().apply {
                    putString("day", dayShort(date))
                    putString("date", date.dayOfMonth.toString())
                    putInt("steps", steps)
                    putBoolean("isToday", date == today)
                    putDouble("distance", calculateDistance(steps))
                    putDouble("calories", calculateCalories(steps))
                    putBoolean("goalCompleted", steps >= dailyGoal)
                    putBoolean("future", isFutureDay)
                }
            )
        }

        val prevSteps = getStepsRange(start.minusWeeks(1), start.minusWeeks(1).plusDays(6))
        val diff = totalSteps - prevSteps
        val improvement = if (prevSteps > 0) diff.toDouble() / prevSteps * 100 else 0.0

        val safeMinSteps = if (minSteps == Int.MAX_VALUE) 0 else minSteps

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
            putInt("minSteps", safeMinSteps)
            putInt("maxSteps", maxSteps)
            putString("stepsRange", "$safeMinSteps - $maxSteps")
        }
    }

    fun getMonthlyStats(offset: Int): WritableMap {
        val today = LocalDate.now()
        val target = today.plusMonths(offset.toLong())
        val totalDaysInMonth = target.lengthOfMonth()

        val array = Arguments.createArray()

        var totalSteps = 0
        var mostSteps = 0
        var leastSteps = Int.MAX_VALUE
        var mostActiveDay = ""
        var leastActiveDay = ""
        var activeMinutes = 0
        var goalDays = 0

        var minSteps = Int.MAX_VALUE
        var maxSteps = 0

        val lastValidDay = when {
            today.year > target.year || (today.year == target.year && today.monthValue > target.monthValue)
            -> totalDaysInMonth

            today.year < target.year || (today.year == target.year && today.monthValue < target.monthValue)
            -> 0

            else -> today.dayOfMonth
        }

        for (d in 1..totalDaysInMonth) {
            val date = target.withDayOfMonth(d)
            val isFutureDay = d > lastValidDay

            val steps = if (isFutureDay) 0 else getSteps(date.toString())

            if (!isFutureDay) {
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

                if (steps > maxSteps) maxSteps = steps
                if (steps < minSteps) minSteps = steps
            }

            array.pushMap(
                Arguments.createMap().apply {
                    putString("day", d.toString())
                    putInt("steps", steps)
                    putBoolean("isToday", date == today)
                    putDouble("distance", calculateDistance(steps))
                    putDouble("calories", calculateCalories(steps))
                    putBoolean("future", isFutureDay)
                }
            )
        }

        val safeMinSteps = if (minSteps == Int.MAX_VALUE) 0 else minSteps

        val prevStart = target.minusMonths(1).withDayOfMonth(1)
        val prevEnd = prevStart.withDayOfMonth(prevStart.lengthOfMonth())
        val prevSteps = getStepsRange(prevStart, prevEnd)

        val diff = totalSteps - prevSteps
        val improvement = if (prevSteps > 0) diff.toDouble() / prevSteps * 100 else 0.0

        return Arguments.createMap().apply {
            putInt("goal", dailyGoal * totalDaysInMonth)
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
            putInt("minSteps", safeMinSteps)
            putInt("maxSteps", maxSteps)
            putString("stepsRange", "$safeMinSteps - $maxSteps")
        }
    }

    fun getYearlyStats(offset: Int): WritableMap {
        val today = LocalDate.now()
        val targetYear = today.year + offset

        var totalSteps = 0
        var mostSteps = 0
        var leastSteps = Int.MAX_VALUE
        var mostActiveMonth = ""
        var leastActiveMonth = ""

        var minSteps = Int.MAX_VALUE
        var maxSteps = 0

        val monthsArray = Arguments.createArray()

        val lastValidMonth = when {
            today.year > targetYear -> 12
            today.year < targetYear -> 0
            else -> today.monthValue
        }

        for (m in 1..12) {
            val start = LocalDate.of(targetYear, m, 1)
            val end = start.withDayOfMonth(start.lengthOfMonth())

            val isFutureMonth = m > lastValidMonth

            val steps = if (isFutureMonth) 0 else getStepsRange(start, end)

            if (!isFutureMonth) {
                totalSteps += steps
                if (steps > mostSteps) {
                    mostSteps = steps
                    mostActiveMonth = start.month.getDisplayName(TextStyle.FULL, locale)
                }
                if (steps < leastSteps) {
                    leastSteps = steps
                    leastActiveMonth = start.month.getDisplayName(TextStyle.FULL, locale)
                }
                if (steps > maxSteps) maxSteps = steps
                if (steps < minSteps) minSteps = steps
            }

            monthsArray.pushMap(
                Arguments.createMap().apply {
                    putString("month", start.month.getDisplayName(TextStyle.SHORT, locale))
                    putInt("steps", steps)
                    putDouble("distance", calculateDistance(steps))
                    putDouble("calories", calculateCalories(steps))
                    putBoolean("future", isFutureMonth)
                }
            )
        }

        val safeMinSteps = if (minSteps == Int.MAX_VALUE) 0 else minSteps

        return Arguments.createMap().apply {
            putInt("goal", dailyGoal * 365)
            putInt("totalSteps", totalSteps)
            putDouble("totalDistance", calculateDistance(totalSteps))
            putDouble("totalCalories", calculateCalories(totalSteps))
            putString("mostActiveMonth", mostActiveMonth)
            putString("leastActiveMonth", leastActiveMonth)
            putArray("months", monthsArray)
            putInt("minSteps", safeMinSteps)
            putInt("maxSteps", maxSteps)
            putString("stepsRange", "$safeMinSteps - $maxSteps")
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

    private fun computeWeekStartShift(today: LocalDate, start: String): Int {
        val startValue = when (start.lowercase()) {
            "monday" -> 1
            "sunday" -> 7
            "saturday" -> 6
            else -> 1
        }

        val todayValue = today.dayOfWeek.value
        return (todayValue - startValue + 7) % 7
    }
}
