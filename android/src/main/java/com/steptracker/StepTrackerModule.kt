package com.steptracker

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.text.SimpleDateFormat
import java.util.*

class StepTrackerModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext),
  SensorEventListener {

  private val sensorManager: SensorManager =
    reactContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private val prefs: SharedPreferences =
    reactContext.getSharedPreferences("StepTrackerPrefs", Context.MODE_PRIVATE)

  private var activeSensor: Sensor? = null
  private var useCounter = false

  init {
    val counter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    val detector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    when {
      counter != null -> {
        activeSensor = counter
        useCounter = true
        Log.d("StepTrackerModule", "Usando TYPE_STEP_COUNTER")
      }
      detector != null -> {
        activeSensor = detector
        useCounter = false
        Log.d("StepTrackerModule", "Usando TYPE_STEP_DETECTOR")
      }
      else -> {
        Log.e("StepTrackerModule", "No hay sensores de pasos disponibles")
      }
    }
  }

  @ReactMethod
  fun startTracking() {
    Log.d("StepTrackerModule", "startTracking() fue llamado desde JS")
    activeSensor?.let {
      sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
    } ?: Log.e("StepTrackerModule", "No hay sensor de pasos disponible")
  }

  private fun getTodayDate(): String {
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
  }

  private fun getOffsetKey(): String {
    return "offset_" + getTodayDate()
  }

  private fun saveStepsToHistory(date: String, steps: Float) {
    prefs.edit().putFloat("history_$date", steps).apply()
  }

  override fun onSensorChanged(event: SensorEvent?) {
    event ?: return
    val today = getTodayDate()
    val lastDate = prefs.getString("last_date", today) ?: today

    if (today != lastDate) {
      val yesterdaySteps = prefs.getFloat("steps_today", 0f)
      Log.d("StepTrackerModule", "Cambio de día. Guardando $yesterdaySteps pasos en historial del $lastDate")
      saveStepsToHistory(lastDate, yesterdaySteps)

      if (useCounter && event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
        prefs.edit().putFloat(getOffsetKey(), event.values[0]).apply()
        prefs.edit().putFloat("steps_today", 0f).apply()
      }

      if (!useCounter && event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
        prefs.edit().putFloat(getOffsetKey(), 0f).apply()
        prefs.edit().putFloat("steps_today", 0f).apply()
      }

      prefs.edit().putString("last_date", today).apply()
    }

    if (useCounter && event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
      val totalSteps = event.values[0]
      Log.d("StepTrackerModule", "TOTAL pasos desde reinicio: $totalSteps")

      val offset = prefs.getFloat(getOffsetKey(), -1f)
      val stepsToday = if (offset < 0) {
        prefs.edit().putFloat(getOffsetKey(), totalSteps).apply()
        prefs.edit().putString("last_date", today).apply()
        0f
      } else {
        totalSteps - offset
      }

      prefs.edit().putFloat("steps_today", stepsToday).apply()
      Log.d("StepTrackerModule", "Pasos hoy (offset aplicado): $stepsToday")
      sendStepEvent(stepsToday)

    } else if (!useCounter && event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
      val current = prefs.getFloat("steps_today", 0f) + 1f
      prefs.edit().putFloat("steps_today", current).apply()
      Log.d("StepTrackerModule", "Paso individual detectado (total hoy: $current)")
      sendStepEvent(current)
    }
  }

  private fun sendStepEvent(steps: Float) {
    val params = Arguments.createMap().apply {
      putDouble("steps", steps.toDouble())
    }
    reactContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
      .emit("onStep", params)
  }

  override fun getName(): String = "StepTrackerModule"

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
  }

  @ReactMethod
  fun addListener(eventName: String) {
    Log.d("StepTrackerModule", "Listener añadido: $eventName")
  }

  @ReactMethod
  fun removeListeners(count: Int) {
    Log.d("StepTrackerModule", "Listeners removidos: $count")
  }
}
