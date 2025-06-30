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
import com.steptracker.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, baseNotif(ctx, steps))
        }

        private fun baseNotif(ctx: Context, steps: Int) =
            NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Contando pasos")
                .setContentText("Hoy: $steps pasos")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
    }

    /* deps */
    private val sm   by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val step by lazy { sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val dao   by lazy { StepsDatabase.get(this).dao() }
    private val io    = CoroutineScope(Dispatchers.IO)

    /* state */
    private var lastCounter = -1f
    private var todayOffset = -1f

    /* ---------- lifecycle ---------- */
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, baseNotif(this, 0))

        lastCounter = prefs.getFloat("last_counter", -1f)
        todayOffset = prefs.getFloat(offsetKey(todayStr()), -1f)

        step?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        updateNotifFromDb()
    }

    override fun onDestroy() {
        prefs.edit().putFloat("last_counter", lastCounter).apply()
        sm.unregisterListener(this)
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /* ---------- sensor ---------- */
    override fun onSensorChanged(ev: SensorEvent?) {
        ev ?: return
        val counter = ev.values[0]
        val nowMs   = System.currentTimeMillis()
        val today   = todayStr(nowMs)

        // 1ª lectura o reinicio
        if (lastCounter < 0f || counter < lastCounter) {
            initOrFixOffset(counter, nowMs)
            persistBase(counter, nowMs, today)
            broadcastAndNotify(counter - todayOffset)
            lastCounter = counter
            return
        }

        // cambio de día
        if (prefs.getString("prev_date", today) != today) {
            initOrFixOffset(counter, nowMs)
            prefs.edit().putString("prev_date", today).apply()
        }

        val delta = counter - lastCounter
        if (delta <= 0f || delta > 2000f) { lastCounter = counter; return }

        val stepsToday = counter - todayOffset

        // Guardar en Room
        io.launch {
            dao.upsertDaily(DailySummary(today, stepsToday.toInt()))
            dao.insertHourly(
                listOf(HourlyBreakdown(today, hourStr(nowMs).toInt(), delta.toInt()))
            )
        }

        // (Compat) seguir actualizando prefs horario
        saveHourlyPrefs(today, hourStr(nowMs), delta)
        prefs.edit().putFloat("last_counter", counter).apply()

        broadcastAndNotify(stepsToday)
        lastCounter = counter
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /* ---------- helpers ---------- */
    private fun broadcastAndNotify(steps: Float) {
        sendBroadcast(Intent(ACTION_STEPS_UPDATE).putExtra(EXTRA_STEPS, steps.toInt()))
        updateNotificationExternally(this, steps.toInt())
    }

    private fun saveHourlyPrefs(date: String, hour: String, delta: Float) {
        val key = "hourly_$date"
        val obj = JSONObject(prefs.getString(key, "{}") ?: "{}")
        obj.put(hour, obj.optDouble(hour, 0.0) + delta)
        prefs.edit().putString(key, obj.toString()).apply()
    }

    private fun initOrFixOffset(counter: Float, timeMs: Long) {
        val today = todayStr(timeMs)
        val key   = offsetKey(today)
        val saved = prefs.getFloat(key, -1f)
        if (saved < 0f) {
            prefs.edit().putFloat(key, counter).apply()
            todayOffset = counter
        } else {
            if (counter > saved) {
                val missed = counter - saved
                io.launch {
                    val prev = dao.summaryFor(today)?.steps ?: 0
                    dao.upsertDaily(DailySummary(today, prev + missed.toInt()))
                }
                prefs.edit().putFloat(key, counter).apply()
            }
            todayOffset = prefs.getFloat(key, counter)
        }
    }

    private fun persistBase(counter: Float, timeMs: Long, date: String) {
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
                    CHANNEL_ID, "Conteo de pasos", NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    private fun updateNotifFromDb() {
        io.launch {
            val steps = dao.summaryFor(todayStr())?.steps ?: 0
            updateNotificationExternally(this@StepTrackerService, steps)
        }
    }

    /* utils */
    private fun todayStr(ms: Long = System.currentTimeMillis()) =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))

    private fun hourStr(ms: Long) =
        SimpleDateFormat("HH", Locale.getDefault()).format(Date(ms))

    private fun offsetKey(date: String) = "offset_$date"
}
