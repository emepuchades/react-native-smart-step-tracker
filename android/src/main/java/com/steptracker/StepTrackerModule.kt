package com.steptracker

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule

class StepTrackerModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext),
  SensorEventListener {

  private val sensorManager: SensorManager =
    reactContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

  private val stepSensor: Sensor? =
    sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

  @ReactMethod
  fun startTracking() {
    stepSensor?.let {
      sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
    } ?: Log.e("StepTrackerModule", "❌ Sensor TYPE_STEP_COUNTER no disponible")
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

  override fun onSensorChanged(event: SensorEvent?) {
      when (event?.sensor?.type) {
          Sensor.TYPE_STEP_COUNTER -> {
              val steps = event.values[0]
              Log.d("StepTrackerModule", "👣 Contador de pasos: $steps")
              sendStepEvent(steps)
          }
          Sensor.TYPE_STEP_DETECTOR -> {
              Log.d("StepTrackerModule", "👣 Paso detectado individual")
              sendStepEvent(1f)
          }
      }
  }

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
