package com.steptracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class StepTrackerService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "steptracker_channel"
        const val NOTIF_ID   = 1
        const val PREFS_NAME = "StepTrackerPrefs"

        const val ACTION_STEPS_UPDATE = "com.steptracker.STEPS_UPDATE"
        const val EXTRA_STEPS         = "steps_today"

        fun updateNotificationExternally(ctx: Context, steps: Int) {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIF_ID, buildBaseNotification(ctx, steps))
        }

        private fun buildBaseNotification(ctx: Context, steps: Int) =
            NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Contando pasos")
                .setContentText("Hoy: $steps pasos")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(
                    NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
    }

    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val stepSensor    by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    private val prefs         by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private var lastCounter        = -1f
    private var todayOffsetCounter = -1f

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForeground(NOTIF_ID, buildBaseNotification(this, 0))

        lastCounter        = prefs.getFloat("last_counter", -1f)
        todayOffsetCounter = prefs.getFloat(offsetKey(todayStr()), -1f)

        stepSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        updateNotificationFromPrefs()
    }

    override fun onDestroy() {
        prefs.edit()
            .putFloat("last_counter", lastCounter)
            .apply()

        sensorManager.unregisterListener(this)
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(ev: SensorEvent?) {
        ev ?: return
        val counter = ev.values[0]
        val nowMs   = System.currentTimeMillis()
        val today   = todayStr(nowMs)

        if (lastCounter < 0f) {
            initOrFixOffset(counter, nowMs)
            persistBaseState(counter, nowMs, today)
            broadcastAndNotify(counter - todayOffsetCounter)
            lastCounter = counter
            return
        }

        if (counter < lastCounter) {
            initOrFixOffset(counter, nowMs)
            persistBaseState(counter, nowMs, today)
            broadcastAndNotify(0f)
            lastCounter = counter
            return
        }

        val prevDate = prefs.getString("prev_date", today)
        if (prevDate != today) {
            initOrFixOffset(counter, nowMs)
            prefs.edit().putString("prev_date", today).apply()
        }

        val delta = counter - lastCounter
        if (delta <= 0f || delta > 2000f) {
            lastCounter = counter
            return
        }

        saveHourly(today, hourStr(nowMs), delta)

        val stepsToday = counter - todayOffsetCounter
        prefs.edit()
            .putFloat(historyKey(today), stepsToday)
            .putFloat("last_counter", counter)
            .apply()

        broadcastAndNotify(stepsToday)
        lastCounter = counter
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun broadcastAndNotify(steps: Float) {
        val i = Intent(ACTION_STEPS_UPDATE).putExtra(EXTRA_STEPS, steps.toInt())
        sendBroadcast(i)
        updateNotificationExternally(this, steps.toInt())
    }

    private fun saveHourly(date: String, hour: String, delta: Float) {
        val key  = hourlyKey(date)
        val json = JSONObject(prefs.getString(key, "{}") ?: "{}")
        json.put(hour, json.optDouble(hour, 0.0) + delta)
        prefs.edit().putString(key, json.toString()).apply()
    }

    private fun initOrFixOffset(counter: Float, timeMs: Long) {
        val today = todayStr(timeMs)
        val key   = offsetKey(today)

        val saved = prefs.getFloat(key, -1f)
        if (saved < 0f) {
            prefs.edit().putFloat(key, counter).apply()
            todayOffsetCounter = counter
        } else {
            if (counter > saved) {
                val missed = counter - saved
                val prev   = prefs.getFloat(historyKey(today), 0f)
                prefs.edit()
                    .putFloat(historyKey(today), prev + missed)
                    .putFloat(key, counter)
                    .apply()
            }
            todayOffsetCounter = prefs.getFloat(key, counter)
        }
    }

    private fun persistBaseState(counter: Float, timeMs: Long, date: String) {
        prefs.edit()
            .putString("prev_date", date)
            .putFloat("last_counter", counter)
            .putLong("last_counter_time", timeMs)
            .apply()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
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

    private fun updateNotificationFromPrefs() {
        val steps = prefs.getFloat(historyKey(todayStr()), 0f).toInt()
        updateNotificationExternally(this, steps)
    }

    private fun todayStr(ms: Long = System.currentTimeMillis()) =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))

    private fun hourStr(ms: Long) =
        SimpleDateFormat("HH", Locale.getDefault()).format(Date(ms))

    private fun historyKey(date: String) = "history_$date"
    private fun hourlyKey(date: String)  = "hourly_$date"
    private fun offsetKey(date: String)  = "offset_$date"
}
