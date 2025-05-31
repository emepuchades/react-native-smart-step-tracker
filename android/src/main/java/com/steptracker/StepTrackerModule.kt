package com.steptracker

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class StepTrackerModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val PREFS_NAME = "StepTrackerPrefs"
        private const val DAILY_GOAL_DEFAULT = 10_000
        private const val STRIDE_METERS = 0.78f
        private const val KCAL_PER_STEP = 0.04f
    }

    private val prefs: SharedPreferences =
        reactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
            val steps = prefs.getFloat("history_$today", 0f)
            promise.resolve(buildStatsMap(steps))
        } catch (e: Exception) {
            promise.reject("ERROR_TODAY_STATS", "No se pudieron obtener los datos de hoy", e)
        }
    }

    @ReactMethod
    fun getStepsHistory(promise: Promise) {
        try {
            val history = Arguments.createMap()
            prefs.all.forEach { (key, value) ->
                if (key.startsWith("history_") && value is Float) {
                    history.putDouble(key.removePrefix("history_"), value.toDouble())
                }
            }
            promise.resolve(history)
        } catch (e: Exception) {
            promise.reject("ERROR_HISTORY", "No se pudo obtener el historial", e)
        }
    }

    @ReactMethod
    fun getStepsByHourHistory(date: String, promise: Promise) {
        try {
            val json = JSONObject(prefs.getString("hourly_$date", "{}") ?: "{}")
            val result = Arguments.createMap()
            json.keys().forEach { hour ->
                result.putDouble(hour, json.getDouble(hour))
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("ERROR_HOURLY_HISTORY", "No se pudo obtener el historial horario", e)
        }
    }

    private fun getTodayDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun buildStatsMap(steps: Float): WritableMap = Arguments.createMap().apply {
        val stepsD     = steps.toDouble()
        val caloriesD  = stepsD * KCAL_PER_STEP
        val distanceD  = stepsD * STRIDE_METERS / 1_000
        val progressD  = stepsD / prefs.getInt("daily_goal", DAILY_GOAL_DEFAULT) * 100

        putDouble("steps", stepsD)
        putDouble("calories", caloriesD)
        putDouble("distance", distanceD)
        putDouble("progress", progressD)
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

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
