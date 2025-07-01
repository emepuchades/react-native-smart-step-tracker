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
import com.steptracker.data.StepsDatabaseHelper
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

        private fun buildBaseNotification(ctx: Context, steps: Int) =
            NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Contando pasos")
                .setContentText("Hoy: $steps pasos")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
    }

    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val stepSensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    private val db by lazy { StepsDatabaseHelper(this) }

    private var lastCounter = -1f
    private var todayOffsetCounter = -1f

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForeground(NOTIF_ID, buildBaseNotification(this, 0))

        lastCounter = db.getLastCounter()
        todayOffsetCounter = db.getTodayOffset(todayStr())

        stepSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        updateNotificationFromDb()
    }

    override fun onDestroy() {
        db.setLastCounter(lastCounter)
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

        val prevDate = db.getPrevDate() ?: today
        if (prevDate != today) {
            initOrFixOffset(counter, nowMs)
            db.setPrevDate(today)
        }

        val delta = counter - lastCounter
        if (delta <= 0f || delta > 2000f) {
            lastCounter = counter
            return
        }

        saveHourly(today, hourStr(nowMs), delta)

        val stepsToday = counter - todayOffsetCounter
        db.setStepsForDate(today, stepsToday)
        db.setLastCounter(counter)

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
        db.insertOrUpdateHourly(date, hour.toInt(), delta.toInt())
    }

    private fun initOrFixOffset(counter: Float, timeMs: Long) {
        val today = todayStr(timeMs)
        val saved = db.getTodayOffset(today)
        if (saved < 0f) {
            db.setTodayOffset(today, counter)
            todayOffsetCounter = counter
        } else {
            if (counter > saved) {
                val missed = counter - saved
                val prev = db.getStepsForDate(today)
                db.setStepsForDate(today, prev + missed)
                db.setTodayOffset(today, counter)
            }
            todayOffsetCounter = db.getTodayOffset(today)
        }
    }

    private fun persistBaseState(counter: Float, timeMs: Long, date: String) {
        db.setPrevDate(date)
        db.setLastCounter(counter)
        db.setLastCounterTime(timeMs)
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
        val steps = db.getStepsForDate(todayStr()).toInt()
        updateNotificationExternally(this, steps)
    }

    private fun todayStr(ms: Long = System.currentTimeMillis()) =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))

    private fun hourStr(ms: Long) =
        SimpleDateFormat("HH", Locale.getDefault()).format(Date(ms))
}
