package com.steptracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class StepTrackerService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "steptracker_channel"
        const val NOTIF_ID = 1
        const val PREFS_NAME = "StepTrackerPrefs"
    }

    private val sensorManager by lazy {
        getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    private val stepSensor by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    }
    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private var lastCounter = -1f
    private var lastWallTimeMs = 0L
    private var todayOffsetCounter = -1f

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification(0))
        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(ev: SensorEvent?) {
        ev ?: return
        val counter = ev.values[0]

        val wallTimeMs = System.currentTimeMillis()

        if (lastCounter < 0f || counter < lastCounter) {
            lastCounter = counter
            lastWallTimeMs = wallTimeMs
            initDailyOffsetIfNeeded(counter, wallTimeMs)
            return
        }

        val delta = counter - lastCounter
        if (delta <= 0f || delta > 2000f) return

        val cal = Calendar.getInstance().apply { timeInMillis = wallTimeMs }
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
        val hourStr = SimpleDateFormat("HH", Locale.getDefault()).format(cal.time)
        saveHourlyFor(dateStr, hourStr, delta)

        if (todayOffsetCounter < 0f) initDailyOffsetIfNeeded(counter - delta, wallTimeMs)
        val stepsToday = counter - todayOffsetCounter
        prefs.edit().putFloat("history_$dateStr", stepsToday).apply()

        updateNotification(stepsToday.toInt())

        lastCounter = counter
        lastWallTimeMs = wallTimeMs
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun saveHourlyFor(date: String, hour: String, delta: Float) {
        val key = "hourly_$date"
        val json = JSONObject(prefs.getString(key, "{}") ?: "{}")
        val curr = json.optDouble(hour, 0.0)
        json.put(hour, curr + delta)
        prefs.edit().putString(key, json.toString()).apply()
    }

    private fun offsetKey(date: String) = "offset_$date"

    private fun initDailyOffsetIfNeeded(counter: Float, timeMs: Long) {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timeMs))
        val key = offsetKey(dateStr)

        todayOffsetCounter = if (prefs.contains(key)) {
            prefs.getFloat(key, counter)
        } else {
            prefs.edit().putFloat(key, counter).apply()
            counter
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Conteo de pasos en segundo plano",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    private fun buildNotification(progress: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Contando pasos")
            .setContentText("Hoy: $progress pasos")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun updateNotification(progress: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification(progress))
    }
}
