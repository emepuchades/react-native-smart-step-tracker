package com.steptracker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
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
        // Reiniciar el servicio si fue matado por el sistema operativo.
        // Solo lo hacemos si el permiso ACTIVITY_RECOGNITION está concedido:
        // si no lo está, startForegroundService() arrancaría el servicio pero este
        // no podría llamar startForeground() (requiere el permiso en API 34+),
        // causando ForegroundServiceDidNotStartInTimeException y crash de la app.
        val hasActivityPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        try {
            if (hasActivityPermission) {
                val serviceIntent = Intent(applicationContext, StepTrackerService::class.java)
                ContextCompat.startForegroundService(applicationContext, serviceIntent)
            } else {
                Log.d("StepSyncWorker", "ACTIVITY_RECOGNITION no concedido, omitiendo reinicio del servicio")
            }
        } catch (e: Exception) {
            Log.e("StepSyncWorker", "No se pudo reiniciar StepTrackerService", e)
        }

        val today = getToday()
        val steps = historyRepo.getStepsForDate(today)

        Log.d("StepSyncWorker", "Sync → $steps pasos del día $today")

        if (hasActivityPermission) {
            StepTrackerService.updateNotificationExternally(applicationContext, steps)
        }

        return Result.success()
    }

    private fun getToday(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date())
}
