package com.steptracker

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log

class StepTrackerModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), SensorEventListener {

    companion object {
        private const val EVENT_STEP_STATS = "onStepStats"
        private const val PREFS_NAME = "StepTrackerPrefs"
        private const val DAILY_GOAL_DEFAULT = 10_000
        private const val STRIDE_METERS = 0.78f
        private const val KCAL_PER_STEP = 0.04f
    }

    private val sensorManager =
        reactContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val prefs: SharedPreferences =
        reactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val stepSensor: Sensor? =
      sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private var offset = -1f
    private var lastCounter = -1f
    private var lastDate    = getTodayDate()

    override fun getName() = "StepTrackerModule"


    @ReactMethod
    fun startTracking() {
        Log.d("StepTracker", "startTracking() llamado")
        if (stepSensor == null) Log.w("StepTracker", "NO existe TYPE_STEP_COUNTER")
        stepSensor?.let {
            val ok = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            Log.d("StepTracker", "registerListener ok = $ok")
        }
    }

    @ReactMethod
    fun stopTracking() {
        sensorManager.unregisterListener(this)
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

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val today = getTodayDate()
        val counter = event.values[0]

        val offsetKey = "offset_$today"
        var offset = prefs.getFloat(offsetKey, -1f)
        if (offset < 0f) {
            val prev = prefs.getFloat("counter_at_end_$lastDate", counter)
            offset = prev
            prefs.edit().putFloat(offsetKey, offset).apply()
        }

        val prevKey = "prev_counter_$today"
        val prevCounter = prefs.getFloat(prevKey, counter)
        val deltaSteps = (counter - prevCounter)
            .takeIf { it >= 0f && it < 2000f } ?: 0f
        prefs.edit().putFloat(prevKey, counter).apply()
        val stepsToday = counter - offset
        saveDaily(stepsToday)
        saveHourly(deltaSteps)
        sendStatsToJS(stepsToday)

        if (lastDate != today) {
            prefs.edit()
                .putFloat("counter_at_end_$lastDate", counter)
                .remove("prev_counter_$lastDate")
                .apply()
        }
        lastDate = today
    }



    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}


    private fun getTodayDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun getCurrentHour(): String =
        SimpleDateFormat("HH", Locale.getDefault()).format(Date())

    private fun saveDaily(stepsToday: Float) {
        prefs.edit().putFloat("history_${getTodayDate()}", stepsToday).apply()
    }

    private fun saveHourly(deltaSteps: Float) {
        val key = "hourly_${getTodayDate()}"
        val json = JSONObject(prefs.getString(key, "{}") ?: "{}")
        val hour = getCurrentHour()
        val current = json.optDouble(hour, 0.0)
        json.put(hour, current + deltaSteps)
        prefs.edit().putString(key, json.toString()).apply()
    }

    private fun sendStatsToJS(stepsToday: Float) {
        val map = buildStatsMap(stepsToday)
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(EVENT_STEP_STATS, map)
    }

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


    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
