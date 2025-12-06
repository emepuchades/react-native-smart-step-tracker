package com.steptracker.bridge

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.facebook.react.bridge.*
import com.steptracker.StepSyncWorker
import com.steptracker.StepTrackerService
import com.steptracker.data.steps.ConfigRepository
import com.steptracker.data.steps.StepHistoryRepository
import com.steptracker.data.steps.StepStatsRepository
import com.steptracker.data.steps.StepsDatabaseHelper
import com.steptracker.data.steps.UserPreferencesManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

class StepTrackerModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "StepTrackerModule"

    companion object {
        private const val METERS_PER_STEP = 0.8
        private const val KM_TO_MILES = 0.621371
        private const val KCAL_PER_STEP = 0.04
        private const val KCAL_TO_KJ = 4.184
    }

    private val dbHelper = StepsDatabaseHelper(reactContext)
    private val db = dbHelper.writableDatabase

    private val configRepo = ConfigRepository(db)
    private val prefsManager = UserPreferencesManager(configRepo)
    private val historyRepo = StepHistoryRepository(db, configRepo)
    private val statsRepo = StepStatsRepository(db, prefsManager, historyRepo)

    private fun currentDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun computeWeekStartDate(today: LocalDate, start: String): LocalDate {
        val weekStartDay = when (start.lowercase()) {
            "monday" -> DayOfWeek.MONDAY
            "sunday" -> DayOfWeek.SUNDAY
            "saturday" -> DayOfWeek.SATURDAY
            else -> DayOfWeek.MONDAY
        }
        return today.with(TemporalAdjusters.previousOrSame(weekStartDay))
    }


    @ReactMethod
    fun startTracking() {
        val intent = Intent(reactContext, StepTrackerService::class.java)
        ContextCompat.startForegroundService(reactContext, intent)
    }

    @ReactMethod
    fun stopTracking() {
        val intent = Intent(reactContext, StepTrackerService::class.java)
        reactContext.stopService(intent)
    }

    @ReactMethod
    fun ensureServiceRunning() {
        val intent = Intent().apply {
            setClassName(reactContext, "com.steptracker.StepTrackerService")
            action = "RESTART_SERVICE"
        }
        ContextCompat.startForegroundService(reactContext, intent)
    }

    @ReactMethod
    fun getTodayStats(promise: Promise) {
        try {
            val today = currentDate()
            val steps = historyRepo.getStepsForDate(today)
            promise.resolve(buildBasicStatsMap(steps))
        } catch (e: Exception) {
            promise.reject("ERROR_TODAY_STATS", e)
        }
    }

    private fun buildBasicStatsMap(steps: Int): WritableMap = Arguments.createMap().apply {
        val stepsD = steps.toDouble()
        val goal = historyRepo.getDailyGoal()

        val (distVal, distUnit) = computeDistance(steps)
        val (energyVal, energyUnit) = computeEnergy(steps)

        putDouble("steps", stepsD)
        putDouble("distance", distVal)
        putString("distanceUnit", distUnit)
        putDouble("calories", energyVal)
        putString("energyUnit", energyUnit)

        putDouble("progress", if (goal > 0) (stepsD / goal) * 100 else 0.0)
        putInt("dailyGoal", goal)

        putDouble("time", stepsD / 98)
    }

    @ReactMethod
    fun getStepsHistory(promise: Promise) {
        try {
            val result = Arguments.createMap()
            val cursor = db.rawQuery("SELECT date, steps FROM daily_history", null)

            while (cursor.moveToNext()) {
                result.putInt(cursor.getString(0), cursor.getInt(1))
            }

            cursor.close()
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("ERROR_HISTORY", e)
        }
    }

    @ReactMethod
    fun getStepsByHourHistory(date: String, promise: Promise) {
        try {
            val result = Arguments.createMap()
            val cursor = db.rawQuery(
                "SELECT hour, steps FROM hourly_history WHERE date = ?",
                arrayOf(date)
            )

            while (cursor.moveToNext()) {
                result.putInt(cursor.getInt(0).toString(), cursor.getInt(1))
            }

            cursor.close()
            promise.resolve(result)

        } catch (e: Exception) {
            promise.reject("ERROR_HOURLY_HISTORY", e)
        }
    }

    @ReactMethod
    fun getWeeklySummary(promise: Promise) {
        try {
            val weekStartPref = prefsManager.getWeekStart()
            val today = LocalDate.now()
            val start = computeWeekStartDate(today, weekStartPref)
            val goal = historyRepo.getDailyGoal()

            var totalSteps = 0
            var daysCount = 0
            var activeDays = 0

            for (i in 0..6) {
                val date = start.plusDays(i.toLong())
                if (date.isAfter(today)) break

                val steps = historyRepo.getStepsForDate(date.toString())
                totalSteps += steps
                daysCount++

                if (steps >= goal) {
                    activeDays++
                }
            }

            val prevStart = start.minusWeeks(1)
            val prevEnd = prevStart.plusDays(6)

            val prevCursor = db.rawQuery(
                """
                SELECT SUM(steps)
                FROM daily_history
                WHERE date BETWEEN ? AND ?
                """.trimIndent(),
                arrayOf(prevStart.toString(), prevEnd.toString())
            )

            var prevSteps = 0
            if (prevCursor.moveToFirst()) {
                prevSteps = prevCursor.getInt(0)
            }
            prevCursor.close()

            val diff = totalSteps - prevSteps
            val improvement = if (prevSteps > 0) {
                diff.toDouble() / prevSteps * 100.0
            } else {
                0.0
            }

            val (totalDistance, distanceUnit) = computeDistance(totalSteps)
            val (totalEnergy, energyUnit) = computeEnergy(totalSteps)

            val timeMinutes = if (totalSteps > 0) totalSteps / 98.0 else 0.0

            val avgStepsPerDay = if (daysCount > 0) totalSteps / daysCount else 0

            val result = Arguments.createMap().apply {
                putInt("totalSteps", totalSteps)
                putInt("averageSteps", avgStepsPerDay)
                putInt("daysCount", daysCount)
                putInt("activeDays", activeDays)

                putDouble("totalDistance", totalDistance)
                putString("distanceUnit", distanceUnit)

                putDouble("totalCalories", totalEnergy)
                putString("energyUnit", energyUnit)

                putDouble("timeMinutes", timeMinutes)
                putInt("avgStepsPerDay", avgStepsPerDay)

                putDouble("improvement", improvement)
            }

            promise.resolve(result)

        } catch (e: Exception) {
            promise.reject("ERR_WEEKLY", e)
        }
    }

    @ReactMethod
    fun getWeeklyProgress(promise: Promise) {
        try {
            val weekStartPref = prefsManager.getWeekStart()
            val distanceUnit = prefsManager.getDistanceUnit()
            val energyUnit = prefsManager.getEnergyUnit()
            val goal = historyRepo.getDailyGoal()

            val today = LocalDate.now()
            val start = computeWeekStartDate(today, weekStartPref)

            val array = Arguments.createArray()

            for (i in 0..6) {
                val date = start.plusDays(i.toLong())
                val isFuture = date.isAfter(today)
                val steps = if (isFuture) 0 else historyRepo.getStepsForDate(date.toString())

                val (distance, _) = computeDistance(steps)
                val (calories, _) = computeEnergy(steps)

                val percentage = if (goal > 0) steps.toDouble() / goal * 100 else 0.0

                val dayKey = getDayKey(date.dayOfWeek)

                val map = Arguments.createMap().apply {
                    putString("date", date.toString())
                    putString("dayKey", dayKey)
                    putInt("steps", steps)
                    putDouble("distance", distance)
                    putString("distanceUnit", distanceUnit)
                    putDouble("calories", calories)
                    putString("energyUnit", energyUnit)
                    putInt("goal", goal)
                    putDouble("percentage", percentage)
                    putBoolean("goalCompleted", steps >= goal)
                    putBoolean("isToday", date == today)
                    putBoolean("future", isFuture)
                }

                array.pushMap(map)
            }

            promise.resolve(array)

        } catch (e: Exception) {
            promise.reject("ERR_PROGRESS", e)
        }
    }

    @ReactMethod
    fun getStreakCount(promise: Promise) {
        try {
            val cursor = db.rawQuery(
                "SELECT date, steps, goal FROM daily_history ORDER BY date DESC",
                null
            )

            val today = currentDate()
            var streak = 0

            while (cursor.moveToNext()) {
                val date = cursor.getString(0)
                val steps = cursor.getInt(1)
                val goal = cursor.getInt(2)

                if (date == today) continue
                if (steps >= goal) streak++ else break
            }

            cursor.close()
            promise.resolve(streak)

        } catch (e: Exception) {
            promise.reject("ERROR_STREAK", e)
        }
    }

    @ReactMethod
    fun getDailyStats(date: String, promise: Promise) {
        try {
            promise.resolve(statsRepo.getDailyHourlyStats(date))
        } catch (e: Exception) {
            promise.reject("GET_DAILY_STATS_ERROR", e)
        }
    }

    @ReactMethod
    fun getWeeklyStats(lang: String, offset: Int, promise: Promise) {
        try {
            promise.resolve(statsRepo.getWeeklyStats(offset))
        } catch (e: Exception) {
            promise.reject("GET_WEEKLY_STATS_ERROR", e)
        }
    }

    @ReactMethod
    fun getMonthlyStats(lang: String, offset: Int, promise: Promise) {
        try {
            promise.resolve(statsRepo.getMonthlyStats(offset))
        } catch (e: Exception) {
            promise.reject("GET_MONTHLY_STATS_ERROR", e)
        }
    }

    @ReactMethod
    fun getYearlyStats(lang: String, offset: Int, promise: Promise) {
        try {
            promise.resolve(statsRepo.getYearlyStats(offset))
        } catch (e: Exception) {
            promise.reject("GET_YEARLY_STATS_ERROR", e)
        }
    }

    @ReactMethod fun setUserLanguage(lang: String) = prefsManager.setLanguage(lang)
    @ReactMethod fun setWeekStart(day: String) = prefsManager.setWeekStart(day)
    @ReactMethod fun setDistanceUnit(unit: String) = prefsManager.setDistanceUnit(unit)
    @ReactMethod fun setEnergyUnit(unit: String) = prefsManager.setEnergyUnit(unit)

    @ReactMethod
    fun getUserPreferences(promise: Promise) {
        try {
            promise.resolve(prefsManager.getUserPreferences())
        } catch (e: Exception) {
            promise.reject("ERROR_PREFS", e)
        }
    }

    @ReactMethod
    fun scheduleBackgroundSync() {
        val request = PeriodicWorkRequestBuilder<StepSyncWorker>(15, TimeUnit.MINUTES)
            .addTag("step_sync")
            .build()

        WorkManager.getInstance(reactContext).enqueueUniquePeriodicWork(
            "step_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun getDayKey(day: DayOfWeek): String {
        return when (day) {
            DayOfWeek.MONDAY -> "mon"
            DayOfWeek.TUESDAY -> "tue"
            DayOfWeek.WEDNESDAY -> "wed"
            DayOfWeek.THURSDAY -> "thu"
            DayOfWeek.FRIDAY -> "fri"
            DayOfWeek.SATURDAY -> "sat"
            DayOfWeek.SUNDAY -> "sun"
        }
    }

    private fun computeDistance(steps: Int): Pair<Double, String> {
        val km = steps * (METERS_PER_STEP / 1000.0)
        return when (prefsManager.getDistanceUnit()) {
            "miles" -> km * KM_TO_MILES to "mi"
            else -> km to "km"
        }
    }

    private fun computeEnergy(steps: Int): Pair<Double, String> {
        val kcal = steps * KCAL_PER_STEP
        return when (prefsManager.getEnergyUnit()) {
            "kj" -> kcal * KCAL_TO_KJ to "kJ"
            else -> kcal to "kcal"
        }
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
