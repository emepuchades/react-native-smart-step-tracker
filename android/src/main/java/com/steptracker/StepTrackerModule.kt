package com.steptracker

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.*
import com.steptracker.data.StepsDatabaseHelper
import androidx.work.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class StepTrackerModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val DAILY_GOAL_DEFAULT = 10_000
        private const val STRIDE_METERS = 0.78f
        private const val KCAL_PER_STEP = 0.04f
    }

    private val db = StepsDatabaseHelper(reactContext)

    override fun getName() = "StepTrackerModule"

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
    fun getTodayStats(promise: Promise) {
        try {
            val today = getTodayDate()
            val steps = db.getStepsForDate(today)
            promise.resolve(buildStatsMap(steps))
        } catch (e: Exception) {
            promise.reject("ERROR_TODAY_STATS", "No se pudieron obtener los datos de hoy", e)
        }
    }

    @ReactMethod
    fun getStepsHistory(promise: Promise) {
        try {
            val history = Arguments.createMap()
            val cursor = db.getAllDailyHistory()
            while (cursor.moveToNext()) {
                val date = cursor.getString(cursor.getColumnIndexOrThrow("date"))
                val steps = cursor.getInt(cursor.getColumnIndexOrThrow("steps"))
                history.putDouble(date, steps.toDouble())
            }
            cursor.close()
            promise.resolve(history)
        } catch (e: Exception) {
            promise.reject("ERROR_HISTORY", "No se pudo obtener el historial", e)
        }
    }

    @ReactMethod
    fun getStepsByHourHistory(date: String, promise: Promise) {
        try {
            val hourlyMap = Arguments.createMap()
            val cursor = db.getHourlyForDate(date)
            while (cursor.moveToNext()) {
                val hour = cursor.getInt(cursor.getColumnIndexOrThrow("hour")).toString()
                val steps = cursor.getInt(cursor.getColumnIndexOrThrow("steps"))
                hourlyMap.putDouble(hour, steps.toDouble())
            }
            cursor.close()
            promise.resolve(hourlyMap)
        } catch (e: Exception) {
            promise.reject("ERROR_HOURLY_HISTORY", "No se pudo obtener el historial horario", e)
        }
    }

    private fun getTodayDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun buildStatsMap(steps: Int): WritableMap = Arguments.createMap().apply {
        val stepsD     = steps.toDouble()
        val caloriesD  = stepsD * KCAL_PER_STEP
        val distanceD  = stepsD * STRIDE_METERS / 1_000
        val dailyGoal  = db.getDailyGoal()
        val progressD  = stepsD / dailyGoal * 100
        val timeD      = stepsD / 98

        putDouble("steps", stepsD)
        putDouble("calories", caloriesD)
        putDouble("distance", distanceD)
        putDouble("progress", progressD)
        putInt("dailyGoal", dailyGoal)
        putDouble("time", timeD)
    }

    @ReactMethod
    fun ensureServiceRunning() {
        val ctx = reactApplicationContext
        val intent = Intent().apply {
            setClassName(ctx, "com.steptracker.StepTrackerService")
            action = "RESTART_SERVICE"
        }
        ContextCompat.startForegroundService(ctx, intent)
    }

    @ReactMethod
    fun scheduleBackgroundSync() {
        val workRequest = PeriodicWorkRequestBuilder<StepSyncWorker>(15, TimeUnit.MINUTES)
            .addTag("step_sync")
            .build()

        WorkManager.getInstance(reactApplicationContext)
            .enqueueUniquePeriodicWork("step_sync", ExistingPeriodicWorkPolicy.KEEP, workRequest)
    }

    @ReactMethod
    fun getWeeklySummary(promise: Promise) {
        try {
            val summary = db.getWeeklySummary()
            val map = Arguments.createMap().apply {
                putInt("totalSteps", summary["totalSteps"] as Int)
                putInt("averageSteps", summary["averageSteps"] as Int)
                putInt("activeDays", summary["activeDays"] as Int)
                putInt("daysCount", summary["daysCount"] as Int)
            }
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("ERROR_WEEKLY_SUMMARY", "No se pudo obtener el resumen semanal", e)
        }
    }

    @ReactMethod
    fun setDailyGoal(goal: Int, promise: Promise) {
        try {
            db.setDailyGoal(goal)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERROR_SET_DAILY_GOAL", "No se pudo actualizar el objetivo global", e)
        }
    }

    @ReactMethod
    fun getDailyGoal(promise: Promise) {
        try {
            val goal = db.getDailyGoal()
            promise.resolve(goal)
        } catch (e: Exception) {
            promise.reject("ERROR_GET_DAILY_GOAL", "No se pudo obtener el objetivo global", e)
        }
    }

    @ReactMethod
    fun getStreakCount(promise: Promise) {
        try {
            val streak = db.getStreakCount()
            promise.resolve(streak)
        } catch (e: Exception) {
            promise.reject("ERROR_STREAK", "No se pudo calcular la racha", e)
        }
    }

    @ReactMethod
    fun getDailyStats(date: String, promise: Promise) {
        try {
            val result = db.getDailyHourlyStatsForDate(date)
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("GET_DAILY_STATS_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun getWeeklyProgress(promise: Promise) {
        try {
            val result = db.getWeeklyProgress()
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("ERROR_WEEKLY_PROGRESS", "No se pudo obtener el progreso semanal", e)
        }
    }

    @ReactMethod
    fun getWeeklyStats(language: String, offset: Int, promise: Promise) {
        try {
            val result = db.getWeeklyStats(language, offset)
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("GET_WEEKLY_STATS_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun getMonthlyStats(language: String, offset: Int, promise: Promise) {
        try {
            val result = db.getMonthlyStats(language, offset)
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("GET_MONTHLY_STATS_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun getYearlyStats(language: String, offset: Int, promise: Promise)  {
        try {
            val result = db.getYearlyStats(language, offset)
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("GET_YEARLY_STATS_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun setUserLanguage(lang: String) {
        db.setUserLanguage(lang)
    }

    @ReactMethod
    fun getUserLanguage(promise: Promise) {
        try {
            val lang = db.getUserLanguage()
            promise.resolve(lang)
        } catch (e: Exception) {
            promise.reject("ERROR_USER_LANGUAGE", "No se pudo obtener el idioma", e)
        }
    }

    @ReactMethod
    fun addListener(eventName: String) {}
    @ReactMethod
    fun removeListeners(count: Int) {}
}
