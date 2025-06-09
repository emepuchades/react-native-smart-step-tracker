package com.steptracker

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.*

class StepSyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(
            StepTrackerService.PREFS_NAME, Context.MODE_PRIVATE
        )

        val today  = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val steps  = prefs.getFloat("history_$today", 0f).toInt()
        Log.d("StepSyncWorker", "Subo $steps pasos del $today al servidor…")

        StepTrackerService.updateNotificationExternally(applicationContext, steps)

        return Result.success()
    }
}
