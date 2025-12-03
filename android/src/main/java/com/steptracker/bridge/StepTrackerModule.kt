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

    private fun currentDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    // HISTORY
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

    // WEEKLY SUMMARY
    @ReactMethod
    fun getWeeklySummary(promise: Promise) {
        try {
            val cursor = db.rawQuery(
                """
                SELECT SUM(steps), AVG(steps), COUNT(*)
                FROM daily_history
                WHERE date >= date('now','-6 day')
            """.trimIndent(),
                null
            )

            val result = Arguments.createMap()
            if (cursor.moveToFirst()) {
                result.putInt("totalSteps", cursor.getInt(0))
                result.putInt("averageSteps", cursor.getDouble(1).toInt())
                result.putInt("daysCount", cursor.getInt(2))
                result.putInt("activeDays", cursor.getInt(2))
            }

            cursor.close()
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("ERR_WEEKLY", e)
        }
    }

    // WEEKLY PROGRESS
    @ReactMethod
    fun getWeeklyProgress(promise: Promise) {
        try {
            val goal = historyRepo.getDailyGoal()

            val cursor = db.rawQuery(
                """
                SELECT date, steps FROM daily_history
                WHERE date >= date('now','-6 day')
                ORDER BY date ASC
            """.trimIndent(),
                null
            )

            val array = Arguments.createArray()

            while (cursor.moveToNext()) {
                val date = cursor.getString(0)
                val steps = cursor.getInt(1)

                val (distVal, distUnit) = computeDistance(steps)
                val (energyVal, energyUnit) = computeEnergy(steps)

                val map = Arguments.createMap().apply {
                    putString("date", date)
                    putInt("steps", steps)
                    putDouble("distance", distVal)
                    putDouble("calories", energyVal)
                    putString("distanceUnit", distUnit)
                    putString("energyUnit", energyUnit)
                    putInt("goal", goal)
                    putDouble("percentage", if (goal > 0) steps.toDouble() / goal * 100 else 0.0)
                    putBoolean("goalCompleted", steps >= goal)
                }

                array.pushMap(map)
            }

            cursor.close()
            promise.resolve(array)
        } catch (e: Exception) {
            promise.reject("ERR_PROGRESS", e)
        }
    }

    // STREAK
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

    // STATS
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

    // CONFIG
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

    // WORKER
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

    // UTILS
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
