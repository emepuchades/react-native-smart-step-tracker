package com.steptracker

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.steptracker.data.StepsDatabaseHelper
import java.text.SimpleDateFormat
import java.util.*

class StepSyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    private val db = StepsDatabaseHelper(applicationContext)

    override fun doWork(): Result {
        val today = getTodayDate()
        val steps = db.getStepsForDate(today).toInt()

        Log.d("StepSyncWorker", "Subo $steps pasos del $today al servidor…")

        StepTrackerService.updateNotificationExternally(applicationContext, steps)

        return Result.success()
    }

    private fun getTodayDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }
}
