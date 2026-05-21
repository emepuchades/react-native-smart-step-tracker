package com.steptracker.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.steptracker.NotificationStrings
import com.steptracker.data.steps.ConfigRepository
import com.steptracker.data.steps.StepsDatabaseHelper
import com.steptracker.data.steps.UserPreferencesManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object {
        const val BACKUP_CHANNEL_ID = "backup_channel"
        const val BACKUP_NOTIFICATION_ID = 9200
    }

    private val dbHelper = StepsDatabaseHelper(applicationContext)
    private val db = dbHelper.readableDatabase

    override fun doWork(): Result {
        return try {
            val json = buildBackupJson()
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "steptracker_backup_$dateStr.json"
            val docsDir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: applicationContext.filesDir
            docsDir.mkdirs()
            val file = File(docsDir, fileName)
            file.writeText(json)
            showBackupNotification(file.absolutePath)
            Log.d("BackupWorker", "Backup created: ${file.absolutePath}")
            Result.success()
        } catch (e: Exception) {
            Log.e("BackupWorker", "Backup failed", e)
            Result.failure()
        }
    }

    private fun buildBackupJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("created_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date()))

        val configArray = JSONArray()
        val configCursor = db.rawQuery("SELECT key, value FROM config", null)
        while (configCursor.moveToNext()) {
            configArray.put(JSONObject().apply {
                put("key", configCursor.getString(0))
                put("value", configCursor.getString(1))
            })
        }
        configCursor.close()
        root.put("config", configArray)

        val dailyArray = JSONArray()
        val dailyCursor = db.rawQuery("SELECT date, steps, offset, goal FROM daily_history", null)
        while (dailyCursor.moveToNext()) {
            dailyArray.put(JSONObject().apply {
                put("date", dailyCursor.getString(0))
                put("steps", dailyCursor.getInt(1))
                put("offset", dailyCursor.getInt(2))
                put("goal", dailyCursor.getInt(3))
            })
        }
        dailyCursor.close()
        root.put("daily_history", dailyArray)

        val journeyArray = JSONArray()
        val journeyCursor = db.rawQuery("SELECT * FROM journeys", null)
        while (journeyCursor.moveToNext()) {
            val obj = JSONObject()
            for (i in 0 until journeyCursor.columnCount) {
                val value = journeyCursor.getString(i)
                if (value != null) obj.put(journeyCursor.getColumnName(i), value)
            }
            journeyArray.put(obj)
        }
        journeyCursor.close()
        root.put("journeys", journeyArray)

        val journeyLogArray = JSONArray()
        val journeyLogCursor = db.rawQuery("SELECT * FROM journey_daily_log", null)
        while (journeyLogCursor.moveToNext()) {
            val obj = JSONObject()
            for (i in 0 until journeyLogCursor.columnCount) {
                val value = journeyLogCursor.getString(i)
                if (value != null) obj.put(journeyLogCursor.getColumnName(i), value)
            }
            journeyLogArray.put(obj)
        }
        journeyLogCursor.close()
        root.put("journey_daily_log", journeyLogArray)

        return root.toString(2)
    }

    private fun showBackupNotification(filePath: String) {
        val ns = NotificationStrings.forLanguage(
            UserPreferencesManager(ConfigRepository(db)).getLanguage()
        )
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BACKUP_CHANNEL_ID,
                ns.channelBackups,
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, BACKUP_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle(ns.backupCreated)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${ns.savedIn}$filePath"))
            .setAutoCancel(true)
            .build()

        nm.notify(BACKUP_NOTIFICATION_ID, notification)
    }
}
