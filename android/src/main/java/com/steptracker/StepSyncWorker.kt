package com.steptracker

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.*

class StepSyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        Log.d("StepSyncWorker", "Worker ejecutado: ${System.currentTimeMillis()}")

        val prefs = applicationContext.getSharedPreferences(
            StepTrackerService.PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val now = System.currentTimeMillis()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
        val offsetKey = "offset_$today"
        val historyKey = "history_$today"

        val lastKnownCounter = prefs.getFloat("last_counter", -1f)
        if (lastKnownCounter < 0f) {
            Log.w("StepSyncWorker", "No se encontró last_counter. Salgo.")
            return Result.success()
        }

        val lastCounterTime = prefs.getLong("last_counter_time", -1L)
        val lastCounterDate = if (lastCounterTime > 0) {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(lastCounterTime))
        } else null

        val lastSavedDate = prefs.getString("prev_date", null)
        val isNewDay = lastSavedDate != today || !prefs.contains(offsetKey)

        val stepsToday: Float

        if (isNewDay) {
            prefs.edit()
                .putFloat(offsetKey, lastKnownCounter)
                .putString("prev_date", today)
                .putFloat(historyKey, 0f)
                .remove("prev_counter")
                .remove("prev_time")
                .apply()

            Log.d("StepSyncWorker", "Nuevo día detectado. Offset ➜ $lastKnownCounter, pasos = 0")
            stepsToday = 0f

        } else if (lastCounterDate == today) {
            val offset = prefs.getFloat(offsetKey, lastKnownCounter)
            stepsToday = (lastKnownCounter - offset).coerceAtLeast(0f)

            prefs.edit().putFloat(historyKey, stepsToday).apply()
        } else {
            stepsToday = prefs.getFloat(historyKey, 0f)
            Log.d(
                "StepSyncWorker",
                "Contador de otro día ($lastCounterDate). No recalculo. Pasos = $stepsToday"
            )
        }

        StepTrackerService.updateNotificationExternally(
            applicationContext,
            stepsToday.toInt()
        )

        return Result.success()
    }
}
