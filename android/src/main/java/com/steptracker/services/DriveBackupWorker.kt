package com.steptracker.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.steptracker.data.steps.StepsDatabaseHelper
import com.steptracker.data.steps.ConfigRepository
import com.steptracker.data.steps.UserPreferencesManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DriveBackupWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object {
        const val WORK_NAME = "drive_auto_backup"
        private const val CHANNEL_ID = "drive_backup_channel"
        private const val NOTIFICATION_ID = 9250
        private const val TAG = "DriveBackupWorker"
    }

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(
            "steptracker_google", Context.MODE_PRIVATE
        )
        val email = prefs.getString("email", null)
            ?: run {
                Log.d(TAG, "No Google account linked, skipping backup")
                return Result.success()
            }

        val dbHelper = StepsDatabaseHelper(applicationContext)
        val db = dbHelper.writableDatabase
        val configRepo = ConfigRepository(db)
        val prefsManager = UserPreferencesManager(configRepo)

        if (!prefsManager.isDriveAutoBackupEnabled()) {
            Log.d(TAG, "Auto backup disabled, skipping")
            db.close()
            return Result.success()
        }

        return try {
            val token = getSilentToken(email)
                ?: run {
                    Log.w(TAG, "Could not get token silently, will retry next schedule")
                    db.close()
                    // No falla — simplemente lo intentará en la próxima ejecución periódica
                    return Result.success()
                }

            val json = buildBackupJson(db)
            uploadToDrive(token, json)

            val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            prefsManager.setLastBackupDate(isoDate)
            Log.d(TAG, "Drive backup completed at $isoDate")
            showSuccessNotification()
            db.close()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Drive backup failed", e)
            db.close()
            // No usar Result.retry() para evitar bucles agresivos si hay error permanente
            Result.success()
        }
    }

    /**
     * Intenta obtener el access token de Google Drive sin mostrar UI.
     * Devuelve null si el token requiere interacción del usuario (hasResolution = true)
     * o si falla por cualquier motivo.
     */
    private fun getSilentToken(email: String): String? {
        return try {
            val authRequest = AuthorizationRequest.Builder()
                .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.appdata")))
                .setAccount(android.accounts.Account(email, "com.google"))
                .build()

            val authResult = Tasks.await(
                Identity.getAuthorizationClient(applicationContext).authorize(authRequest)
            )

            if (authResult.hasResolution()) {
                // Necesita UI — imposible en background
                Log.w(TAG, "Token requires user interaction, skipping this run")
                null
            } else {
                authResult.accessToken
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSilentToken failed", e)
            null
        }
    }

    private fun buildBackupJson(db: android.database.sqlite.SQLiteDatabase): String {
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

        // Journeys
        val journeyArray = JSONArray()
        val journeyCursor = db.rawQuery("SELECT * FROM journeys", null)
        while (journeyCursor.moveToNext()) {
            val obj = JSONObject()
            for (i in 0 until journeyCursor.columnCount) {
                obj.put(journeyCursor.getColumnName(i), journeyCursor.getString(i))
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

    private fun uploadToDrive(token: String, json: String) {
        val fileName = "stepjourney_backup.json"

        // Buscar archivo existente
        val encodedQuery = URLEncoder.encode("name='$fileName'", "UTF-8")
        val listConn = URL(
            "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=$encodedQuery&fields=files(id)"
        ).openConnection() as java.net.HttpURLConnection
        listConn.setRequestProperty("Authorization", "Bearer $token")
        val listCode = listConn.responseCode
        val listText = if (listCode in 200..299)
            listConn.inputStream.bufferedReader().readText()
        else {
            val err = listConn.errorStream?.bufferedReader()?.readText() ?: ""
            listConn.disconnect()
            throw Exception("LIST_ERROR ($listCode): $err")
        }
        listConn.disconnect()

        val existingId = JSONObject(listText)
            .optJSONArray("files")
            ?.takeIf { it.length() > 0 }
            ?.getJSONObject(0)
            ?.getString("id")

        // Subir con multipart
        val boundary = "drive_boundary_${System.currentTimeMillis()}"
        val metadataJson = if (existingId == null)
            """{"name":"$fileName","parents":["appDataFolder"]}"""
        else "{}"

        val uploadUrl = if (existingId != null)
            URL("https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=multipart")
        else
            URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")

        val uploadConn = uploadUrl.openConnection() as java.net.HttpURLConnection
        uploadConn.requestMethod = if (existingId != null) "PATCH" else "POST"
        uploadConn.setRequestProperty("Authorization", "Bearer $token")
        uploadConn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        uploadConn.doOutput = true

        val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadataJson\r\n" +
                "--$boundary\r\nContent-Type: application/json\r\n\r\n$json\r\n--$boundary--\r\n"
        uploadConn.outputStream.write(body.toByteArray(Charsets.UTF_8))

        val code = uploadConn.responseCode
        val resp = if (code in 200..299)
            uploadConn.inputStream.bufferedReader().readText()
        else
            uploadConn.errorStream?.bufferedReader()?.readText() ?: ""
        uploadConn.disconnect()

        if (code !in 200..299) {
            throw Exception("UPLOAD_ERROR ($code): $resp")
        }
    }

    private fun showSuccessNotification() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Drive Backup", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                }
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("StepJourney")
            .setContentText("Copia de seguridad guardada en Google Drive")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }
}
