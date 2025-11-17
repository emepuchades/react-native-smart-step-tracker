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
import com.steptracker.data.steps.*
import java.text.SimpleDateFormat
import java.util.*

class StepTrackerService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "steptracker_channel"
        const val NOTIF_ID = 1

        const val ACTION_STEPS_UPDATE = "com.steptracker.STEPS_UPDATE"
        const val EXTRA_STEPS = "steps_today"

        fun updateNotificationExternally(ctx: Context, steps: Int) {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIF_ID, buildBaseNotification(ctx, steps))
        }

        private fun buildBaseNotification(ctx: Context, steps: Int): Notification =
            NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Contando pasos")
                .setContentText("Hoy: $steps pasos")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
    }

    // Repos
    private val dbHelper by lazy { StepsDatabaseHelper(this) }
    private val db by lazy { dbHelper.writableDatabase }

    private val configRepo by lazy { ConfigRepository(db) }
    private val historyRepo by lazy { StepHistoryRepository(db, configRepo) }

    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val stepSensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }

    private var lastCounter = -1f
    private var todayOffsetCounter: Int = -1

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForeground(NOTIF_ID, buildBaseNotification(this, 0))

        lastCounter = configRepo.get("last_counter")?.toFloatOrNull() ?: -1f
        todayOffsetCounter = historyRepo.getTodayOffset(todayStr())

        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        updateNotificationFromDb()
    }

    override fun onDestroy() {
        configRepo.set("last_counter", lastCounter.toString())
        sensorManager.unregisterListener(this)
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(ev: SensorEvent?) {
        ev ?: return
        val counter = ev.values[0]
        val nowMs = System.currentTimeMillis()
        val today = todayStr(nowMs)

        if (lastCounter < 0f) {
            initOrFixOffset(counter, nowMs)
            persistState(counter, nowMs, today)
            broadcastAndNotify((counter - todayOffsetCounter).toInt())
            lastCounter = counter
            return
        }

        if (counter < lastCounter) {
            initOrFixOffset(counter, nowMs)
            persistState(counter, nowMs, today)
            broadcastAndNotify(0)
            lastCounter = counter
            return
        }

        val prevDate = configRepo.get("prev_date") ?: today
        if (prevDate != today) {
            initOrFixOffset(counter, nowMs)
            configRepo.set("prev_date", today)
        }

        val delta = counter - lastCounter
        if (delta <= 0f || delta > 2000f) {
            lastCounter = counter
            return
        }

        saveHourly(today, hourStr(nowMs), delta)

        val stepsToday = (counter - todayOffsetCounter)
        historyRepo.setStepsForDate(today, stepsToday.toInt())
        configRepo.set("last_counter", counter.toString())

        broadcastAndNotify(stepsToday.toInt())
        lastCounter = counter
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun broadcastAndNotify(steps: Int) {
        val intent = Intent(ACTION_STEPS_UPDATE).putExtra(EXTRA_STEPS, steps)
        sendBroadcast(intent)
        updateNotificationExternally(this, steps)
    }

    private fun saveHourly(date: String, hour: String, delta: Float) {
        historyRepo.insertOrUpdateHourly(date, hour.toInt(), delta.toInt())
    }

    private fun initOrFixOffset(counter: Float, timeMs: Long) {
        val today = todayStr(timeMs)
        val saved = historyRepo.getTodayOffset(today)

        if (saved < 0) {
            historyRepo.setTodayOffset(today, counter.toInt())
            todayOffsetCounter = counter.toInt()
        } else {
            if (counter > saved) {
                val missed = (counter - saved)
                val prev = historyRepo.getStepsForDate(today)
                historyRepo.setStepsForDate(today, (prev + missed).toInt())
                historyRepo.setTodayOffset(today, counter.toInt())
            }
            todayOffsetCounter = historyRepo.getTodayOffset(today)
        }
    }

    private fun persistState(counter: Float, timeMs: Long, date: String) {
        configRepo.set("prev_date", date)
        configRepo.set("last_counter", counter.toString())
        configRepo.set("last_counter_time", timeMs.toString())
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

    private fun updateNotificationFromDb() {
        val steps = historyRepo.getStepsForDate(todayStr())
        updateNotificationExternally(this, steps)
    }

    private fun todayStr(ms: Long = System.currentTimeMillis()) =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))

    private fun hourStr(ms: Long) =
        SimpleDateFormat("HH", Locale.getDefault()).format(Date(ms))
}
