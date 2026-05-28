package com.steptracker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // No arrancar el servicio si ACTIVITY_RECOGNITION no está concedido:
            // el servicio de tipo "health" no puede llamar startForeground() sin él
            // (Android 34+/36 lanza SecurityException) y Android mataría la app
            // con ForegroundServiceDidNotStartInTimeException a los 5 s.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION)
                    != PackageManager.PERMISSION_GRANTED) {
                android.util.Log.w("StepTracker", "BootReceiver: ACTIVITY_RECOGNITION no concedido, omitiendo")
                return
            }
            try {
                val serviceIntent = Intent(ctx, StepTrackerService::class.java)
                ContextCompat.startForegroundService(ctx, serviceIntent)
            } catch (e: Exception) {
                android.util.Log.w("StepTracker", "BootReceiver: no se pudo arrancar el servicio", e)
            }
        }
    }
}

