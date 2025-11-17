package com.steptracker

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.steptracker.data.steps.*

import java.text.SimpleDateFormat
import java.util.*

class StepSyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    private val dbHelper = StepsDatabaseHelper(ctx)
    private val db = dbHelper.readableDatabase
    private val configRepo = ConfigRepository(db)
    private val historyRepo = StepHistoryRepository(db, configRepo)

    override fun doWork(): Result {
        val today = getToday()
        val steps = historyRepo.getStepsForDate(today)

        Log.d("StepSyncWorker", "Sync → $steps pasos del día $today")

        StepTrackerService.updateNotificationExternally(applicationContext, steps)

        return Result.success()
    }

    private fun getToday(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date())
}
