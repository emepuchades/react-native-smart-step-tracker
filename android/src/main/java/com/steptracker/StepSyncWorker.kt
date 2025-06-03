package com.steptracker

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONObject

class StepSyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        Log.d("StepSyncWorker", "Worker ejecutado: ${System.currentTimeMillis()}")

        val prefs = applicationContext.getSharedPreferences(StepTrackerService.PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
        val offsetKey = "offset_$today"
        val historyKey = "history_$today"
        val lastKnownCounter = prefs.getFloat("last_counter", -1f)
       
        if (lastKnownCounter < 0f) {
            Log.w("StepSyncWorker", "No se encontró last_counter. No se actualizan pasos.")
            return Result.success()
        }

        val lastSavedDate = prefs.getString("prev_date", null)

        if (lastSavedDate != today || !prefs.contains(offsetKey)) {
            prefs.edit()
                .putFloat(offsetKey, lastKnownCounter)
                .putString("prev_date", today)
                .remove("prev_counter")
                .remove("prev_time")
                .apply()
        }

        val offset = prefs.getFloat(offsetKey, lastKnownCounter)
        val stepsToday = (lastKnownCounter - offset).coerceAtLeast(0f)

        prefs.edit().putFloat(historyKey, stepsToday).apply()

        Log.d("StepSyncWorker", "Pasos guardados desde Worker: $stepsToday")

        StepTrackerService.updateNotificationExternally(applicationContext, stepsToday.toInt())

        return Result.success()
    }
}