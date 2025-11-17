package com.steptracker.data.steps

import android.database.sqlite.SQLiteDatabase
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import java.text.NumberFormat
import java.time.LocalDate
import java.time.Year
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.*

class StepStatsRepository(
    private val db: SQLiteDatabase,
    private val prefs: UserPreferencesManager
) {

    private val dailyGoal get() = 5000

    fun getDailyHourlyStats(date: String): WritableMap {
        val cursor = db.rawQuery(
            "SELECT hour, steps FROM hourly_history WHERE date = ?",
            arrayOf(date)
        )

        val orderedHours = (6..23) + (0..5)
        val hoursArray = Arguments.createArray()

        var totalSteps = 0
        var mostSteps = 0
        var mostActiveHour = "00:00"
        var activeMinutes = 0

        for (hour in orderedHours) {
            val hourString = "%02d:00".format(hour)

            var steps = 0
            if (cursor.moveToFirst()) {
                do {
                    if (cursor.getInt(0) == hour) {
                        steps = cursor.getInt(1)
                        break
                    }
                } while (cursor.moveToNext())
            }

            if (steps > 0) activeMinutes += 60
            if (steps > mostSteps) {
                mostSteps = steps
                mostActiveHour = hourString
            }

            totalSteps += steps

            val map = Arguments.createMap().apply {
                putString("hour", hourString)
                putInt("steps", steps)
                putDouble("distance", steps * 0.0008)
                putDouble("calories", steps * 0.04)
                putBoolean("active", steps > 0)
            }
            hoursArray.pushMap(map)
        }

        cursor.close()

        val goal = getDailyGoal(date)
        val yesterdaySteps = getSteps(date, -1)

        val totalDistance = totalSteps * 0.0008
        val totalCalories = totalSteps * 0.04
        val improvement = if (yesterdaySteps > 0)
            ((totalSteps - yesterdaySteps) * 100) / yesterdaySteps else 0

        val distanceYesterday = yesterdaySteps * 0.0008
        val distanceDiffPercent =
            if (distanceYesterday > 0) ((totalDistance - distanceYesterday) / distanceYesterday) * 100.0 else 0.0

        return Arguments.createMap().apply {
            putArray("hours", hoursArray)
            putInt("totalSteps", totalSteps)
            putDouble("totalDistance", totalDistance)
            putDouble("distanceDiffPercent", distanceDiffPercent)
            putDouble("totalCalories", totalCalories)
            putInt("activeMinutes", activeMinutes)
            putInt("stepsPerMinuteAvg", if (activeMinutes > 0) totalSteps / activeMinutes else 0)
            putInt("goal", goal)
            putBoolean("goalCompleted", totalSteps >= goal)
            putString("mostActiveHour", mostActiveHour)
            putInt("improvement", improvement)
        }
    }

    private fun getDailyGoal(date: String): Int {
        val cursor = db.rawQuery("SELECT goal FROM daily_history WHERE date = ?", arrayOf(date))
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 10000 }
    }

    fun getWeeklyStats(language: String, offset: Int): WritableMap {
        val locale = localeOf(language)
        val weekStart = prefs.getWeekStart()

        val today = LocalDate.now()
        val firstDayOffset = computeFirstDayOfWeekShift(today, weekStart)

        val startOfWeek = today
            .minusDays(firstDayOffset.toLong())
            .plusWeeks(offset.toLong())

        val endOfWeek = startOfWeek.plusDays(6)
        val orderedDays = (0..6).map { startOfWeek.plusDays(it.toLong()) }

        var totalSteps = 0
        var activeMinutes = 0
        var mostSteps = 0
        var mostActiveDay = ""
        var leastSteps = Int.MAX_VALUE
        var leastActiveDay = ""
        var daysGoalCompleted = 0

        val resultArray = Arguments.createArray()
        val dailyGoal = 5000

        val todayDate = LocalDate.now()

        for (date in orderedDays) {
            val steps = getSteps(date.toString())

            totalSteps += steps
            if (steps > 0) activeMinutes += 60
            if (steps >= dailyGoal) daysGoalCompleted++

            if (steps > mostSteps) {
                mostSteps = steps
                mostActiveDay = capitalizeFirst(date.dayOfWeek.getDisplayName(TextStyle.FULL, locale))
            }

            if (steps < leastSteps && !date.isAfter(todayDate)) {
                leastSteps = steps
                leastActiveDay = capitalizeFirst(date.dayOfWeek.getDisplayName(TextStyle.FULL, locale))
            }

            val map = Arguments.createMap()
            map.putString("day", date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale))
            map.putInt("steps", steps)
            map.putString("date", date.dayOfMonth.toString())
            map.putBoolean("isToday", date.isEqual(todayDate))
            map.putDouble("distance", steps * 0.0008)
            map.putDouble("calories", steps * 0.04)
            map.putInt("activeMinutes", if (steps > 0) 60 else 0)
            map.putBoolean("goalCompleted", steps >= dailyGoal)
            resultArray.pushMap(map)
        }

        val prevSteps = getStepsRangeSum(
            startOfWeek.minusWeeks(1),
            startOfWeek.minusWeeks(1).plusDays(6)
        )

        val stepDiff = totalSteps - prevSteps
        val improvement = if (prevSteps > 0) (stepDiff.toDouble() / prevSteps) * 100 else 0.0

        val stepsRange = "${formatNumber(locale, leastSteps)} - ${formatNumber(locale, mostSteps)}"

        return Arguments.createMap().apply {
            putInt("goal", dailyGoal * 7)
            putInt("totalSteps", totalSteps)
            putDouble("totalDistance", totalSteps * 0.0008)
            putDouble("totalCalories", totalSteps * 0.04)
            putInt("activeMinutes", activeMinutes)
            putInt("stepsPerMinuteAvg", if (activeMinutes > 0) totalSteps / activeMinutes else 0)
            putBoolean("goalCompleted", totalSteps >= dailyGoal * 7)
            putString("mostActiveDay", mostActiveDay)
            putString("leastActiveDay", leastActiveDay)
            putInt("mostSteps", mostSteps)
            putInt("leastSteps", leastSteps)
            putString("stepsRange", stepsRange)
            putInt("daysGoalCompleted", daysGoalCompleted)
            putInt("totalDays", orderedDays.size)
            putDouble("improvement", improvement)
            putInt("stepDifference", stepDiff)
            putArray("days", resultArray)
            putString("startDate", startOfWeek.toString())
            putString("endDate", endOfWeek.toString())
        }
    }

    fun getMonthlyStats(language: String, offset: Int): WritableMap {
        val locale = localeOf(language)
        val today = LocalDate.now()
        val targetMonth = today.plusMonths(offset.toLong())

        val days = targetMonth.lengthOfMonth()
        var totalSteps = 0
        var activeMinutes = 0
        var mostSteps = 0
        var mostActiveDay = ""
        var leastSteps = Int.MAX_VALUE
        var leastActiveDay = ""
        var daysGoalCompleted = 0

        val resultArray = Arguments.createArray()
        val dailyGoal = 5000
        val isCurrentMonth = offset == 0

        for (day in 1..days) {
            val date = targetMonth.withDayOfMonth(day)
            val steps = getSteps(date.toString())

            totalSteps += steps
            if (steps > 0) activeMinutes += 60
            if (steps >= dailyGoal) daysGoalCompleted++

            if (steps > mostSteps) {
                mostSteps = steps
                mostActiveDay = formatDayName(date, language)
            }

            if (steps < leastSteps && !date.isAfter(today))
                leastSteps = steps.also { leastActiveDay = formatDayName(date, language) }

            val map = Arguments.createMap().apply {
                putString("day", day.toString())
                putInt("steps", steps)
                putBoolean("isToday", date.isEqual(today))
                putDouble("distance", steps * 0.0008)
                putDouble("calories", steps * 0.04)
                putInt("activeMinutes", if (steps > 0) 60 else 0)
                putBoolean("goalCompleted", steps >= dailyGoal)
            }
            resultArray.pushMap(map)
        }

        val totalDistance = totalSteps * 0.0008
        val totalCalories = totalSteps * 0.04
        val stepsPerMinute = if (activeMinutes > 0) totalSteps / activeMinutes else 0

        val prevStart = targetMonth.minusMonths(1).withDayOfMonth(1)
        val prevEnd = prevStart.withDayOfMonth(prevStart.lengthOfMonth())

        val prevSteps =
            getStepsRangeSum(prevStart, if (isCurrentMonth) prevStart.plusDays(today.dayOfMonth.toLong() - 1) else prevEnd)

        val diff = totalSteps - prevSteps
        val improvement = if (prevSteps > 0) (diff.toDouble() / prevSteps) * 100 else 0.0

        val stepsRange = "${formatNumber(locale, leastSteps)} - ${formatNumber(locale, mostSteps)}"

        return Arguments.createMap().apply {
            putInt("goal", dailyGoal * 30)
            putInt("totalSteps", totalSteps)
            putDouble("totalDistance", totalDistance)
            putDouble("totalCalories", totalCalories)
            putInt("activeMinutes", activeMinutes)
            putInt("stepsPerMinuteAvg", stepsPerMinute)
            putBoolean("goalCompleted", totalSteps >= dailyGoal * 30)
            putString("mostActiveDay", mostActiveDay)
            putString("leastActiveDay", leastActiveDay)
            putInt("mostSteps", mostSteps)
            putInt("leastSteps", leastSteps)
            putString("stepsRange", stepsRange)
            putInt("daysGoalCompleted", daysGoalCompleted)
            putInt("totalDays", days)
            putDouble("improvement", improvement)
            putInt("stepDifference", diff)
            putArray("days", resultArray)
        }
    }

    fun getYearlyStats(language: String, offset: Int): WritableMap {
        val locale = localeOf(language)
        val today = LocalDate.now()

        val targetYear = today.year + offset
        val isCurrentYear = offset == 0

        val shortMonthsEs = listOf("Ene","Feb","Mar","Abr","May","Jun","Jul","Ago","Sep","Oct","Nov","Dic")
        val shortMonthsEn = listOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")

        val fullMonthsEs = listOf(
            "Enero","Febrero","Marzo","Abril","Mayo","Junio",
            "Julio","Agosto","Septiembre","Octubre","Noviembre","Diciembre"
        )
        val fullMonthsEn = listOf(
            "January","February","March","April","May","June",
            "July","August","September","October","November","December"
        )

        val shortMonths = if (language == "es") shortMonthsEs else shortMonthsEn
        val fullMonths = if (language == "es") fullMonthsEs else fullMonthsEn

        var totalSteps = 0
        var activeMinutes = 0
        var mostSteps = 0
        var mostActiveMonth = ""
        var leastSteps = Int.MAX_VALUE
        var leastActiveMonth = ""
        var monthsGoalCompleted = 0
        var daysGoalCompleted = 0

        val resultArray = Arguments.createArray()
        val dailyGoal = 5000
        val monthlyGoal = dailyGoal * 30
        val yearGoal = monthlyGoal * 12

        for (month in 1..12) {
            val start = LocalDate.of(targetYear, month, 1)
            val end = start.withDayOfMonth(start.lengthOfMonth())

            val steps = getStepsRangeSum(start, end)

            totalSteps += steps
            if (steps > 0) activeMinutes += start.lengthOfMonth() * 60
            if (steps >= monthlyGoal) monthsGoalCompleted++

            if (steps > mostSteps) {
                mostSteps = steps
                mostActiveMonth = fullMonths[month - 1]
            }

            if (steps in 1 until leastSteps) {
                leastSteps = steps
                leastActiveMonth = fullMonths[month - 1]
            }

            val completedDays = getDaysGoalCompleted(start, end, dailyGoal)
            daysGoalCompleted += completedDays

            val map = Arguments.createMap().apply {
                putString("month", shortMonths[month - 1])
                putString("fullMonth", fullMonths[month - 1])
                putInt("steps", steps)
                putDouble("distance", steps * 0.0008)
                putDouble("calories", steps * 0.04)
                putBoolean("goalCompleted", steps >= monthlyGoal)
            }
            resultArray.pushMap(map)
        }

        val totalDistance = totalSteps * 0.0008
        val totalCalories = totalSteps * 0.04
        val stepsPerMinute = if (activeMinutes > 0) totalSteps / activeMinutes else 0
        val goalCompleted = totalSteps >= yearGoal

        val monthsToCompare = if (isCurrentYear) today.monthValue else 12

        val prevStart = LocalDate.of(targetYear - 1, 1, 1)
        val prevEnd = LocalDate.of(targetYear - 1, monthsToCompare, 1)
            .withDayOfMonth(LocalDate.of(targetYear - 1, monthsToCompare, 1).lengthOfMonth())

        val prevSteps = getStepsRangeSum(prevStart, prevEnd)

        val diff = totalSteps - prevSteps
        val improvement = if (prevSteps > 0) (diff.toDouble() / prevSteps) * 100 else 0.0
        val validLeast = if (leastSteps == Int.MAX_VALUE) 0 else leastSteps

        val stepsRange = "${formatNumber(locale, validLeast)} - ${formatNumber(locale, mostSteps)}"
        val totalDays = if (isCurrentYear) today.dayOfYear else if (Year.isLeap(targetYear.toLong())) 366 else 365

        return Arguments.createMap().apply {
            putInt("goal", yearGoal)
            putInt("totalSteps", totalSteps)
            putDouble("totalDistance", totalDistance)
            putDouble("totalCalories", totalCalories)
            putInt("activeMinutes", activeMinutes)
            putInt("stepsPerMinuteAvg", stepsPerMinute)
            putBoolean("goalCompleted", goalCompleted)
            putString("mostActiveMonth", mostActiveMonth)
            putString("leastActiveMonth", leastActiveMonth)
            putInt("mostSteps", mostSteps)
            putInt("leastSteps", validLeast)
            putString("stepsRange", stepsRange)
            putInt("monthsGoalCompleted", monthsGoalCompleted)
            putInt("daysGoalCompleted", daysGoalCompleted)
            putInt("totalDays", totalDays)
            putDouble("improvement", improvement)
            putInt("stepDifference", diff)
            putArray("months", resultArray)
        }
    }

    private fun getSteps(date: String): Int {
        val cursor = db.rawQuery(
            "SELECT steps FROM daily_history WHERE date = ?",
            arrayOf(date)
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun getSteps(date: String, offsetDays: Int): Int {
        val cursor = db.rawQuery(
            "SELECT steps FROM daily_history WHERE date = date(?, ? || ' day')",
            arrayOf(date, offsetDays.toString())
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun getStepsRangeSum(start: LocalDate, end: LocalDate): Int {
        val cursor = db.rawQuery(
            "SELECT SUM(steps) FROM daily_history WHERE date BETWEEN ? AND ?",
            arrayOf(start.toString(), end.toString())
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun getDaysGoalCompleted(start: LocalDate, end: LocalDate, goal: Int): Int {
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM daily_history WHERE date BETWEEN ? AND ? AND steps >= ?",
            arrayOf(start.toString(), end.toString(), goal.toString())
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun computeFirstDayOfWeekShift(today: LocalDate, weekStart: String): Int {
        return when (weekStart) {
            "sunday" -> if (today.dayOfWeek.value == 7) 0 else today.dayOfWeek.value
            "monday" -> today.dayOfWeek.value - 1
            else -> 0
        }
    }

    private fun capitalizeFirst(s: String): String {
        return s.replaceFirstChar {
            if (it.isLowerCase()) it.uppercase() else it.toString()
        }
    }

    private fun formatDayName(date: LocalDate, language: String): String {
        val locale = localeOf(language)
        val dayName = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        return "${capitalizeFirst(dayName)} ${date.dayOfMonth}"
    }

    private fun localeOf(language: String): Locale {
        return if (language == "es") Locale("es", "ES") else Locale("en", "US")
    }

    private fun formatNumber(locale: Locale, n: Int): String {
        return NumberFormat.getInstance(locale).format(n)
    }
}
