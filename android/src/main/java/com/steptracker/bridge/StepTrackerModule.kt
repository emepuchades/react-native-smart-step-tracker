package com.steptracker

import android.content.Intent
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.*
import com.steptracker.data.steps.*
import androidx.work.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class StepTrackerModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "StepTrackerModule"

    // DEPENDENCIAS
    private val dbHelper = StepsDatabaseHelper(reactContext)
    private val db = dbHelper.writableDatabase

    private val configRepo = ConfigRepository(db)
    private val prefsManager = UserPreferencesManager(configRepo)
    private val historyRepo = StepHistoryRepository(db, configRepo)
    private val statsRepo = StepStatsRepository(db, prefsManager)

    // BACKGROUND SERVICE
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

    // BASIC STATS
    @ReactMethod
    fun getTodayStats(promise: Promise) {
        try {
            val today = currentDate()
            val steps = historyRepo.getStepsForDate(today)
            promise.resolve(buildBasicStatsMap(steps))
        } catch (e: Exception) {
            promise.reject("ERROR_TODAY_STATS", "No se pudieron obtener los datos de hoy", e)
        }
    }

    private fun buildBasicStatsMap(steps: Int): WritableMap = Arguments.createMap().apply {
        val stepsD = steps.toDouble()
        val distance = stepsD * 0.78 / 1000
        val calories = stepsD * 0.04
        val goal = historyRepo.getDailyGoal()

        putDouble("steps", stepsD)
        putDouble("calories", calories)
        putDouble("distance", distance)
        putDouble("progress", (stepsD / goal) * 100)
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
            promise.reject("ERROR_HISTORY", "No se pudo obtener el historial", e)
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
            promise.reject("ERROR_HOURLY_HISTORY", "No se pudo obtener historial por hora", e)
        }
    }

    // WEEKLY SUMMARY (RESTORED)
    @ReactMethod
    fun getWeeklySummary(promise: Promise) {
        try {
            val cursor = db.rawQuery(
                """
                SELECT SUM(steps) as total,
                       AVG(steps) as avg,
                       COUNT(*) as days
                FROM daily_history
                WHERE date >= date('now', '-6 day')
                """.trimIndent(),
                emptyArray()
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
            val dailyGoal = historyRepo.getDailyGoal()

            val cursor = db.rawQuery(
                """
                SELECT date, steps 
                FROM daily_history
                WHERE date >= date('now', '-6 day')
                ORDER BY date ASC
                """.trimIndent(),
                emptyArray()
            )

            val array = Arguments.createArray()

            if (cursor.moveToFirst()) {
                do {
                    val date = cursor.getString(0)
                    val steps = cursor.getInt(1)

                    val map = Arguments.createMap().apply {
                        putString("date", date)
                        putInt("steps", steps)
                        putDouble("distance", steps * 0.0008)
                        putDouble("calories", steps * 0.04)
                        putInt("goal", dailyGoal)
                        putDouble("percentage", (steps.toDouble() / dailyGoal) * 100)
                        putBoolean("goalCompleted", steps >= dailyGoal)
                    }

                    array.pushMap(map)

                } while (cursor.moveToNext())
            }

            cursor.close()
            promise.resolve(array)

        } catch (e: Exception) {
            promise.reject("ERR_PROGRESS", e)
        }
    }
    // STREAK COUNT 
    @ReactMethod
    fun getStreakCount(promise: Promise) {
        try {
            val cursor = db.rawQuery(
                "SELECT date, steps, goal FROM daily_history ORDER BY date DESC",
                null
            )

            var streak = 0
            val today = currentDate()

            while (cursor.moveToNext()) {
                val date = cursor.getString(0)
                val steps = cursor.getInt(1)
                val goal = cursor.getInt(2)

                if (date == today) continue
                if (steps >= goal) streak++
                else break
            }

            cursor.close()
            promise.resolve(streak)

        } catch (e: Exception) {
            promise.reject("ERROR_STREAK", "No se pudo calcular la racha", e)
        }
    }

    // ADVANCED STATS
    @ReactMethod
    fun getDailyStats(date: String, promise: Promise) {
        try {
            promise.resolve(statsRepo.getDailyHourlyStats(date))
        } catch (e: Exception) {
            promise.reject("GET_DAILY_STATS_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun getWeeklyStats(language: String, offset: Int, promise: Promise) {
        try {
            promise.resolve(statsRepo.getWeeklyStats(language, offset))
        } catch (e: Exception) {
            promise.reject("GET_WEEKLY_STATS_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun getMonthlyStats(language: String, offset: Int, promise: Promise) {
        try {
            promise.resolve(statsRepo.getMonthlyStats(language, offset))
        } catch (e: Exception) {
            promise.reject("GET_MONTHLY_STATS_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun getYearlyStats(language: String, offset: Int, promise: Promise) {
        try {
            promise.resolve(statsRepo.getYearlyStats(language, offset))
        } catch (e: Exception) {
            promise.reject("GET_YEARLY_STATS_ERROR", e.message, e)
        }
    }

    // CONFIG
    @ReactMethod fun setUserLanguage(lang: String) = prefsManager.setLanguage(lang)

    @ReactMethod
    fun getUserLanguage(promise: Promise) {
        try {
            promise.resolve(prefsManager.getLanguage())
        } catch (e: Exception) {
            promise.reject("ERROR_USER_LANGUAGE", e)
        }
    }

    @ReactMethod fun setWeekStart(day: String) = prefsManager.setWeekStart(day)

    @ReactMethod
    fun getUserPreferences(promise: Promise) {
        try {
            promise.resolve(prefsManager.getUserPreferences())
        } catch (e: Exception) {
            promise.reject("ERROR_PREFERENCES", e)
        }
    }

    // BACKGROUND SYNC WORKER
    @ReactMethod
    fun scheduleBackgroundSync() {
        val workRequest = PeriodicWorkRequestBuilder<StepSyncWorker>(15, TimeUnit.MINUTES)
            .addTag("step_sync")
            .build()

        WorkManager.getInstance(reactContext).enqueueUniquePeriodicWork(
            "step_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
