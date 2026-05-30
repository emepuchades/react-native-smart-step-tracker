package com.steptracker.bridge

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.net.URL
import java.net.URLEncoder
import com.steptracker.StepSyncWorker
import com.steptracker.NotificationStrings
import com.steptracker.StepTrackerService
import com.steptracker.services.BackupWorker
import com.steptracker.services.DriveBackupWorker
import com.steptracker.data.steps.ConfigRepository
import com.steptracker.data.steps.JourneyRepository
import com.steptracker.data.steps.StepHistoryRepository
import com.steptracker.data.steps.StepStatsRepository
import com.steptracker.data.steps.StepsDatabaseHelper
import com.steptracker.data.steps.UserPreferencesManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

class StepTrackerModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), ActivityEventListener {

    private data class BadgeNotificationPayload(
        val key: String,
        val title: String,
        val description: String,
    )

    override fun getName() = "StepTrackerModule"

    override fun invalidate() {
        try {
            reactContext.unregisterReceiver(stepUpdateReceiver)
        } catch (_: Exception) {}
        super.invalidate()
    }

    companion object {
        private const val METERS_PER_STEP = 0.8
        private const val KM_TO_MILES = 0.621371
        private const val KCAL_PER_STEP = 0.04
        private const val KCAL_TO_KJ = 4.184
        private const val BADGE_CHANNEL_ID = "badge_unlocks_channel"
        private const val BADGE_NOTIFICATION_ID_BASE = 9100
        private const val IMPORT_REQUEST_CODE = 9301
        private const val GOOGLE_SIGN_IN_REQUEST_CODE = 9302  // Selector de cuenta
        private const val GOOGLE_AUTH_REQUEST_CODE = 9303     // Consentimiento Drive
    }

    private var importPromise: Promise? = null
    private var googleSignInPromise: Promise? = null
    private var pendingAuthEmail: String? = null

    private val dbHelper = StepsDatabaseHelper(reactContext)
    private val db = dbHelper.writableDatabase

    private val configRepo = ConfigRepository(db)
    private val prefsManager = UserPreferencesManager(configRepo)
    private val historyRepo = StepHistoryRepository(db, configRepo)
    private val statsRepo = StepStatsRepository(db, prefsManager, historyRepo)
    private val journeyRepo = JourneyRepository(db)

    private val stepUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val steps = intent?.getIntExtra(StepTrackerService.EXTRA_STEPS, -1) ?: return
            if (steps < 0) return
            try {
                val map = buildBasicStatsMap(steps)
                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("StepTrackerUpdate", map)
            } catch (_: Exception) {}
        }
    }

    init {
        android.util.Log.d("StepTrackerModule", "Module initialized, DB version: ${dbHelper.readableDatabase.version}")
        android.util.Log.d("StepTrackerModule", "Database path: ${reactContext.getDatabasePath("steps.db").absolutePath}")
        reactContext.addActivityEventListener(this)
        ensureJourneyTablesExist()
        ContextCompat.registerReceiver(
            reactContext,
            stepUpdateReceiver,
            IntentFilter(StepTrackerService.ACTION_STEPS_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Re-programar backup Drive si estaba activado (sobrevive a reinicios)
        if (prefsManager.isDriveAutoBackupEnabled()) {
            val freq = prefsManager.getDriveBackupFrequency()
            val (interval, unit) = when (freq) {
                "weekly"  -> 7L   to TimeUnit.DAYS
                "monthly" -> 30L  to TimeUnit.DAYS
                "yearly"  -> 365L to TimeUnit.DAYS
                else      -> 1L   to TimeUnit.DAYS
            }
            val wm = WorkManager.getInstance(reactContext)
            val request = androidx.work.PeriodicWorkRequestBuilder<DriveBackupWorker>(interval, unit)
                .addTag(DriveBackupWorker.WORK_NAME)
                .build()
            wm.enqueueUniquePeriodicWork(
                DriveBackupWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
    
    private fun ensureJourneyTablesExist() {
        try {
            val cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='journeys'",
                null
            )
            val journeyTableExists = cursor.moveToFirst()
            cursor.close()
            
            if (!journeyTableExists) {
                android.util.Log.w("StepTrackerModule", "journeys table doesn't exist, creating it...")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS journeys(
                        journeyId TEXT PRIMARY KEY,
                        status TEXT DEFAULT 'active',
                        destination_name TEXT,
                        destination_lat REAL,
                        destination_lon REAL,
                        destination_address TEXT,
                        origin_name TEXT,
                        origin_lat REAL,
                        origin_lon REAL,
                        origin_address TEXT,
                        route_coords TEXT,
                        total_distance_km REAL,
                        checkpoints TEXT,
                        created_at TEXT,
                        started_at TEXT,
                        completed_at TEXT
                    )
                    """
                )
                android.util.Log.d("StepTrackerModule", "created journeys table")
            }
            
            val cursor2 = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='journey_daily_log'",
                null
            )
            val dailyLogTableExists = cursor2.moveToFirst()
            cursor2.close()
            
            if (!dailyLogTableExists) {
                android.util.Log.w("StepTrackerModule", "journey_daily_log table doesn't exist, creating it...")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS journey_daily_log(
                        id TEXT PRIMARY KEY,
                        journeyId TEXT NOT NULL,
                        date TEXT NOT NULL,
                        trip_day_number INTEGER,
                        is_paused INTEGER DEFAULT 0,
                        current_checkpoint INTEGER,
                        current_location_name TEXT,
                        current_location_lat REAL,
                        current_location_lon REAL,
                        total_walked_km_in_journey REAL,
                        progress_percent REAL,
                        created_at TEXT,
                        UNIQUE(journeyId, date),
                        FOREIGN KEY(journeyId) REFERENCES journeys(journeyId)
                    )
                    """
                )
                android.util.Log.d("StepTrackerModule", "created journey_daily_log table")
            }
            
            android.util.Log.d("StepTrackerModule", "Journey tables validation complete")
        } catch (e: Exception) {
            android.util.Log.e("StepTrackerModule", "Error ensuring journey tables exist: ${e.message}", e)
        }
    }

    private fun currentDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun refreshForegroundNotification() {
        try {
            val stepsToday = historyRepo.getStepsForDate(currentDate()).coerceAtLeast(0)
            StepTrackerService.updateNotificationExternally(reactContext, stepsToday)
        } catch (e: Exception) {
            android.util.Log.w("StepTrackerModule", "Unable to refresh notification", e)
        }
    }

    private fun computeWeekStartDate(today: LocalDate, start: String): LocalDate {
        val weekStartDay = when (start.lowercase()) {
            "monday" -> DayOfWeek.MONDAY
            "sunday" -> DayOfWeek.SUNDAY
            "saturday" -> DayOfWeek.SATURDAY
            else -> DayOfWeek.MONDAY
        }
        return today.with(TemporalAdjusters.previousOrSame(weekStartDay))
    }


    /**
     * Android 10+ (API 29) requiere ACTIVITY_RECOGNITION para sensores de pasos.
     * Android 14+ (API 34) y Android 36 exigen el permiso para arrancar un FGS de
     * tipo "health" — incluso antes de llamar startForeground().
     * Si el permiso no está concedido, NO llamamos startForegroundService() porque
     * el servicio no podría llamar startForeground() correctamente y Android lanzaría
     * ForegroundServiceDidNotStartInTimeException a los 5 s.
     */
    private fun hasActivityRecognitionPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            reactContext, Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @ReactMethod
    fun startTracking() {
        if (!hasActivityRecognitionPermission()) {
            android.util.Log.w("StepTracker", "startTracking: ACTIVITY_RECOGNITION no concedido, omitiendo")
            return
        }
        try {
            val intent = Intent(reactContext, StepTrackerService::class.java).apply {
                action = StepTrackerService.ACTION_RE_REGISTER_SENSOR
            }
            ContextCompat.startForegroundService(reactContext, intent)
        } catch (e: Exception) {
            // Android 12+: ForegroundServiceStartNotAllowedException si la app
            // está en segundo plano. El servicio se reiniciará al volver al frente.
            android.util.Log.w("StepTracker", "startTracking: no se pudo arrancar el servicio", e)
        }
    }

    @ReactMethod
    fun stopTracking() {
        val intent = Intent(reactContext, StepTrackerService::class.java)
        reactContext.stopService(intent)
    }

    @ReactMethod
    fun ensureServiceRunning() {
        if (!hasActivityRecognitionPermission()) {
            android.util.Log.w("StepTracker", "ensureServiceRunning: ACTIVITY_RECOGNITION no concedido, omitiendo")
            return
        }
        try {
            val intent = Intent().apply {
                setClassName(reactContext, "com.steptracker.StepTrackerService")
                action = "RESTART_SERVICE"
            }
            ContextCompat.startForegroundService(reactContext, intent)
        } catch (e: Exception) {
            android.util.Log.w("StepTracker", "ensureServiceRunning: no se pudo arrancar el servicio", e)
        }
    }

    @ReactMethod
    fun getTodayStats(promise: Promise) {
        try {
            val today = currentDate()
            val steps = historyRepo.getStepsForDate(today)
            promise.resolve(buildBasicStatsMap(steps))
        } catch (e: Exception) {
            promise.reject("ERROR_TODAY_STATS", e)
        }
    }

    private fun buildBasicStatsMap(steps: Int): WritableMap = Arguments.createMap().apply {
        val stepsD = steps.toDouble()
        val goal = historyRepo.getDailyGoal()

        val (distVal, distUnit) = computeDistance(steps)
        val (energyVal, energyUnit) = computeEnergy(steps)

        putDouble("steps", stepsD)
        putDouble("distance", distVal)
        putString("distanceUnit", distUnit)
        putDouble("calories", energyVal)
        putString("energyUnit", energyUnit)

        putDouble("progress", if (goal > 0) (stepsD / goal) * 100 else 0.0)
        putInt("dailyGoal", goal)

        putDouble("time", stepsD / 98)
    }

    @ReactMethod
    fun getStepsHistory(promise: Promise) {
        try {
            val result = Arguments.createMap()
            val cursor = db.rawQuery("SELECT date, steps FROM daily_history", null)

            while (cursor.moveToNext()) {
                result.putInt(cursor.getString(0), cursor.getInt(1))
            }

            cursor.close()
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("ERROR_HISTORY", e)
        }
    }

    @ReactMethod
    fun getStepsByHourHistory(date: String, promise: Promise) {
        try {
            val result = Arguments.createMap()
            val cursor = db.rawQuery(
                "SELECT hour, steps FROM hourly_history WHERE date = ?",
                arrayOf(date)
            )

            while (cursor.moveToNext()) {
                result.putInt(cursor.getInt(0).toString(), cursor.getInt(1))
            }

            cursor.close()
            promise.resolve(result)

        } catch (e: Exception) {
            promise.reject("ERROR_HOURLY_HISTORY", e)
        }
    }

    @ReactMethod
    fun getWeeklySummary(promise: Promise) {
        try {
            val weekStartPref = prefsManager.getWeekStart()
            val today = LocalDate.now()
            val start = computeWeekStartDate(today, weekStartPref)
            val goal = historyRepo.getDailyGoal()

            var totalSteps = 0
            var daysCount = 0
            var activeDays = 0

            for (i in 0..6) {
                val date = start.plusDays(i.toLong())
                if (date.isAfter(today)) break

                val steps = historyRepo.getStepsForDate(date.toString())
                totalSteps += steps
                daysCount++

                if (steps >= goal) {
                    activeDays++
                }
            }

            val prevStart = start.minusWeeks(1)
            val prevEnd = prevStart.plusDays(6)

            val prevCursor = db.rawQuery(
                """
                SELECT SUM(steps)
                FROM daily_history
                WHERE date BETWEEN ? AND ?
                """.trimIndent(),
                arrayOf(prevStart.toString(), prevEnd.toString())
            )

            var prevSteps = 0
            if (prevCursor.moveToFirst()) {
                prevSteps = prevCursor.getInt(0)
            }
            prevCursor.close()

            val diff = totalSteps - prevSteps
            val improvement = if (prevSteps > 0) {
                diff.toDouble() / prevSteps * 100.0
            } else {
                0.0
            }

            val (totalDistance, distanceUnit) = computeDistance(totalSteps)
            val (totalEnergy, energyUnit) = computeEnergy(totalSteps)

            val timeMinutes = if (totalSteps > 0) totalSteps / 98.0 else 0.0

            val avgStepsPerDay = if (daysCount > 0) totalSteps / daysCount else 0

            val result = Arguments.createMap().apply {
                putInt("totalSteps", totalSteps)
                putInt("averageSteps", avgStepsPerDay)
                putInt("daysCount", daysCount)
                putInt("activeDays", activeDays)

                putDouble("totalDistance", totalDistance)
                putString("distanceUnit", distanceUnit)

                putDouble("totalCalories", totalEnergy)
                putString("energyUnit", energyUnit)

                putDouble("timeMinutes", timeMinutes)
                putInt("avgStepsPerDay", avgStepsPerDay)

                putDouble("improvement", improvement)
            }

            promise.resolve(result)

        } catch (e: Exception) {
            promise.reject("ERR_WEEKLY", e)
        }
    }

    @ReactMethod
    fun getWeeklyProgress(promise: Promise) {
        try {
            val weekStartPref = prefsManager.getWeekStart()
            val distanceUnit = prefsManager.getDistanceUnit()
            val energyUnit = prefsManager.getEnergyUnit()
            val goal = historyRepo.getDailyGoal()

            val today = LocalDate.now()
            val start = computeWeekStartDate(today, weekStartPref)

            val array = Arguments.createArray()

            for (i in 0..6) {
                val date = start.plusDays(i.toLong())
                val isFuture = date.isAfter(today)
                val steps = if (isFuture) 0 else historyRepo.getStepsForDate(date.toString())

                val (distance, _) = computeDistance(steps)
                val (calories, _) = computeEnergy(steps)

                val percentage = if (goal > 0) steps.toDouble() / goal * 100 else 0.0

                val dayKey = getDayKey(date.dayOfWeek)

                val map = Arguments.createMap().apply {
                    putString("date", date.toString())
                    putString("dayKey", dayKey)
                    putInt("steps", steps)
                    putDouble("distance", distance)
                    putString("distanceUnit", distanceUnit)
                    putDouble("calories", calories)
                    putString("energyUnit", energyUnit)
                    putInt("goal", goal)
                    putDouble("percentage", percentage)
                    putBoolean("goalCompleted", steps >= goal)
                    putBoolean("isToday", date == today)
                    putBoolean("future", isFuture)
                }

                array.pushMap(map)
            }

            promise.resolve(array)

        } catch (e: Exception) {
            promise.reject("ERR_PROGRESS", e)
        }
    }

    @ReactMethod
    fun getStreakCount(promise: Promise) {
        try {
            val cursor = db.rawQuery(
                "SELECT date, steps, goal FROM daily_history ORDER BY date DESC",
                null
            )

            val today = currentDate()
            var streak = 0

            while (cursor.moveToNext()) {
                val date = cursor.getString(0)
                val steps = cursor.getInt(1)
                val goal = cursor.getInt(2)

                if (date == today) continue
                if (steps >= goal) streak++ else break
            }

            cursor.close()
            promise.resolve(streak)

        } catch (e: Exception) {
            promise.reject("ERROR_STREAK", e)
        }
    }

    @ReactMethod
    fun getDailyStats(date: String, promise: Promise) {
        try {
            promise.resolve(statsRepo.getDailyHourlyStats(date))
        } catch (e: Exception) {
            promise.reject("GET_DAILY_STATS_ERROR", e)
        }
    }

    @ReactMethod
    fun getWeeklyStats(lang: String, offset: Int, promise: Promise) {
        try {
            promise.resolve(statsRepo.getWeeklyStats(offset))
        } catch (e: Exception) {
            promise.reject("GET_WEEKLY_STATS_ERROR", e)
        }
    }

    @ReactMethod
    fun getWeeklyStatsJourney(journeyId: String, lang: String, offset: Int, referenceDate: String?, promise: Promise) {
        try {
            val stats = journeyRepo.getWeeklyStatsJourney(journeyId, offset, historyRepo.getDailyGoal(), referenceDate)
            promise.resolve(applyPreferencesToJourneyPeriodStats(stats))
        } catch (e: Exception) {
            promise.reject("GET_WEEKLY_JOURNEY_STATS_ERROR", e)
        }
    }

    @ReactMethod
    fun getMonthlyStats(lang: String, offset: Int, promise: Promise) {
        try {
            promise.resolve(statsRepo.getMonthlyStats(offset))
        } catch (e: Exception) {
            promise.reject("GET_MONTHLY_STATS_ERROR", e)
        }
    }

    @ReactMethod
    fun getMonthlyStatsJourney(journeyId: String, lang: String, offset: Int, referenceDate: String?, promise: Promise) {
        try {
            val stats = journeyRepo.getMonthlyStatsJourney(journeyId, offset, historyRepo.getDailyGoal(), referenceDate)
            promise.resolve(applyPreferencesToJourneyPeriodStats(stats))
        } catch (e: Exception) {
            promise.reject("GET_MONTHLY_JOURNEY_STATS_ERROR", e)
        }
    }

    @ReactMethod
    fun getPerformanceStatsJourney(journeyId: String, date: String?, promise: Promise) {
        try {
            val stats = journeyRepo.getPerformanceStatsJourney(journeyId, date, historyRepo.getDailyGoal())
            promise.resolve(applyPreferencesToJourneyPerformanceStats(stats))
        } catch (e: Exception) {
            promise.reject("GET_PERFORMANCE_JOURNEY_STATS_ERROR", e)
        }
    }

    @ReactMethod
    fun getYearlyStats(lang: String, offset: Int, promise: Promise) {
        try {
            promise.resolve(statsRepo.getYearlyStats(offset))
        } catch (e: Exception) {
            promise.reject("GET_YEARLY_STATS_ERROR", e)
        }
    }

    @ReactMethod
    fun setUserLanguage(lang: String) {
        prefsManager.setLanguage(lang)
        refreshForegroundNotification()
    }

    @ReactMethod fun setWeekStart(day: String) = prefsManager.setWeekStart(day)

    @ReactMethod
    fun setTutorialSeen(seen: Boolean) {
        prefsManager.setTutorialSeen(seen)
    }

    @ReactMethod
    fun setDriveBackupFrequency(frequency: String) {
        prefsManager.setDriveBackupFrequency(frequency)
        val wm = WorkManager.getInstance(reactContext)
        if (frequency == "off") {
            wm.cancelUniqueWork(DriveBackupWorker.WORK_NAME)
            android.util.Log.d("StepTrackerModule", "Drive auto backup cancelled")
        } else {
            val (interval, unit) = when (frequency) {
                "weekly"  -> 7L   to TimeUnit.DAYS
                "monthly" -> 30L  to TimeUnit.DAYS
                "yearly"  -> 365L to TimeUnit.DAYS
                else      -> 1L   to TimeUnit.DAYS   // "daily" por defecto
            }
            val request = androidx.work.PeriodicWorkRequestBuilder<DriveBackupWorker>(interval, unit)
                .addTag(DriveBackupWorker.WORK_NAME)
                .build()
            wm.enqueueUniquePeriodicWork(
                DriveBackupWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
            android.util.Log.d("StepTrackerModule", "Drive auto backup scheduled: $frequency")
        }
    }

    @ReactMethod
    fun setDailyGoal(goal: Int, promise: Promise) {
        try {
            historyRepo.setDailyGoal(goal)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("ERROR_SET_GOAL", e)
        }
    }

    @ReactMethod
    fun setDistanceUnit(unit: String) {
        prefsManager.setDistanceUnit(unit)
        refreshForegroundNotification()
    }

    @ReactMethod
    fun setEnergyUnit(unit: String) {
        prefsManager.setEnergyUnit(unit)
        refreshForegroundNotification()
    }

    @ReactMethod
    fun setBodyMetrics(weight: Double, height: Double, age: Int, promise: Promise) {
        try {
            val weightVal = if (weight > 0) weight else null
            val heightVal = if (height > 0) height else null
            val ageVal = if (age > 0) age else null
            prefsManager.setBodyMetrics(weightVal, heightVal, ageVal)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("ERROR_SET_BODY_METRICS", e)
        }
    }

    @ReactMethod
    fun getUserPreferences(promise: Promise) {
        try {
            promise.resolve(prefsManager.getUserPreferences())
        } catch (e: Exception) {
            promise.reject("ERROR_PREFS", e)
        }
    }

    @ReactMethod
    fun processUnlockedBadgesForNotifications(unlockedBadgesJson: String, promise: Promise) {
        try {
            val unlockedBadges = parseBadgeNotificationPayloads(unlockedBadgesJson)
            val unlockedKeys = unlockedBadges.map { it.key }.filter { it.isNotBlank() }.toSet()

            if (!prefsManager.areBadgeNotificationsPrimed()) {
                prefsManager.setNotifiedBadgeKeys(prefsManager.getNotifiedBadgeKeys() + unlockedKeys)
                prefsManager.setBadgeNotificationsPrimed(true)
                promise.resolve(0)
                return
            }

            if (unlockedBadges.isEmpty()) {
                promise.resolve(0)
                return
            }

            if (!canPostBadgeNotifications()) {
                promise.resolve(0)
                return
            }

            createBadgeNotificationChannelIfNeeded()

            val notifiedKeys = prefsManager.getNotifiedBadgeKeys().toMutableSet()
            var notifiedCount = 0

            unlockedBadges
                .filter { it.key.isNotBlank() && !notifiedKeys.contains(it.key) }
                .forEach { badge ->
                    if (showBadgeUnlockNotification(badge)) {
                        notifiedKeys.add(badge.key)
                        notifiedCount += 1
                    }
                }

            if (notifiedCount > 0) {
                prefsManager.setNotifiedBadgeKeys(notifiedKeys)
            }

            promise.resolve(notifiedCount)
        } catch (e: Exception) {
            promise.reject("BADGE_NOTIFICATION_ERROR", e)
        }
    }

    @ReactMethod
    fun setBackupFrequency(frequency: String, promise: Promise) {
        try {
            prefsManager.setBackupFrequency(frequency)
            scheduleBackupWork(frequency)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("ERROR_BACKUP_FREQ", e)
        }
    }

    @ReactMethod
    fun createBackup(promise: Promise) {
        try {
            val json = buildBackupJson()
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "steptracker_backup_$dateStr.json"
            val docsDir = reactContext.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
                ?: reactContext.filesDir
            docsDir.mkdirs()
            val file = File(docsDir, fileName)
            file.writeText(json)

            val uri = FileProvider.getUriForFile(
                reactContext,
                "${reactContext.packageName}.fileprovider",
                file,
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val activity = reactContext.currentActivity
            if (activity != null) {
                val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                prefsManager.setLastBackupDate(isoDate)
                activity.startActivity(Intent.createChooser(shareIntent, "Guardar copia de seguridad"))
                promise.resolve(isoDate)
            } else {
                promise.reject("NO_ACTIVITY", "No active activity")
            }
        } catch (e: Exception) {
            promise.reject("BACKUP_ERROR", e)
        }
    }

    private fun scheduleBackupWork(frequency: String) {
        val wm = WorkManager.getInstance(reactContext)
        wm.cancelUniqueWork("step_backup")
        val (interval, unit) = when (frequency) {
            "daily" -> 1L to TimeUnit.DAYS
            "weekly" -> 7L to TimeUnit.DAYS
            "monthly" -> 30L to TimeUnit.DAYS
            else -> return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(interval, unit)
            .addTag("step_backup")
            .build()
        wm.enqueueUniquePeriodicWork("step_backup", ExistingPeriodicWorkPolicy.REPLACE, request)
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

    @ReactMethod
    fun signInWithGoogle(promise: Promise) {
        val activity = reactContext.currentActivity
        if (activity == null) {
            promise.reject("NO_ACTIVITY", "No active activity")
            return
        }
        android.util.Log.d("GoogleSignIn", "Starting Google sign-in flow")
        googleSignInPromise = promise

        // Si hay cuenta guardada, intentar autorizar en silencio primero
        val prefs = reactContext.getSharedPreferences("steptracker_google", android.content.Context.MODE_PRIVATE)
        val savedEmail = prefs.getString("email", null)
        if (savedEmail != null) {
            val authRequest = AuthorizationRequest.Builder()
                .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.appdata")))
                .setAccount(android.accounts.Account(savedEmail, "com.google"))
                .build()
            Identity.getAuthorizationClient(activity)
                .authorize(authRequest)
                .addOnSuccessListener { authResult ->
                    if (!authResult.hasResolution()) {
                        googleSignInPromise = null
                        android.util.Log.d("GoogleSignIn", "Silent auth OK: $savedEmail")
                        val displayName = prefs.getString("displayName", savedEmail) ?: savedEmail
                        val map = Arguments.createMap().apply {
                            putString("email", savedEmail)
                            putString("displayName", displayName)
                        }
                        promise.resolve(map)
                    } else {
                        showAccountPicker(activity)
                    }
                }
                .addOnFailureListener { showAccountPicker(activity) }
        } else {
            showAccountPicker(activity)
        }
    }

    private fun showAccountPicker(activity: android.app.Activity) {
        val intent = android.accounts.AccountManager.newChooseAccountIntent(
            null, null, arrayOf("com.google"), null, null, null, null
        )
        activity.startActivityForResult(intent, GOOGLE_SIGN_IN_REQUEST_CODE)
    }

    @ReactMethod
    fun getGoogleSignInAccount(promise: Promise) {
        try {
            val prefs = reactContext.getSharedPreferences("steptracker_google", android.content.Context.MODE_PRIVATE)
            val email = prefs.getString("email", null)
            if (email != null) {
                val displayName = prefs.getString("displayName", email) ?: email
                val map = Arguments.createMap().apply {
                    putString("email", email)
                    putString("displayName", displayName)
                }
                promise.resolve(map)
            } else {
                promise.resolve(null)
            }
        } catch (e: Exception) {
            promise.reject("ERROR_GOOGLE_ACCOUNT", e.message ?: "Unknown error")
        }
    }

    @ReactMethod
    fun signOutGoogle(promise: Promise) {
        reactContext.getSharedPreferences("steptracker_google", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        promise.resolve(null)
    }

    private fun saveGoogleAccount(email: String, displayName: String) {
        reactContext.getSharedPreferences("steptracker_google", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("email", email)
            .putString("displayName", displayName)
            .apply()
    }

    private fun getAuthToken(): String {
        val activity = reactContext.currentActivity ?: throw Exception("NO_ACTIVITY")
        val prefs = reactContext.getSharedPreferences("steptracker_google", android.content.Context.MODE_PRIVATE)
        val email = prefs.getString("email", null) ?: throw Exception("NO_ACCOUNT")
        val authRequest = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.appdata")))
            .setAccount(android.accounts.Account(email, "com.google"))
            .build()
        val authResult = Tasks.await(Identity.getAuthorizationClient(activity).authorize(authRequest))
        if (authResult.hasResolution()) throw Exception("NEEDS_SIGN_IN")
        return authResult.accessToken ?: throw Exception("NO_TOKEN")
    }

    @ReactMethod
    fun backupToDrive(promise: Promise) {
        Thread {
            try {
                val token = getAuthToken()

                val json = buildBackupJson()
                val fileName = "stepjourney_backup.json"

                // Buscar archivo existente en appDataFolder
                val encodedQuery = URLEncoder.encode("name='$fileName'", "UTF-8")
                val listConn = URL(
                    "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=$encodedQuery&fields=files(id)"
                ).openConnection() as java.net.HttpURLConnection
                listConn.setRequestProperty("Authorization", "Bearer $token")
                val listCode = listConn.responseCode
                val listText = if (listCode in 200..299)
                    listConn.inputStream.bufferedReader().readText()
                else {
                    val errBody = listConn.errorStream?.bufferedReader()?.readText() ?: ""
                    listConn.disconnect()
                    throw Exception("LIST_ERROR ($listCode): $errBody")
                }
                listConn.disconnect()

                val existingId = JSONObject(listText)
                    .optJSONArray("files")
                    ?.takeIf { it.length() > 0 }
                    ?.getJSONObject(0)
                    ?.getString("id")

                android.util.Log.d("GoogleDrive", "Existing file ID: $existingId")

                // Subir o actualizar con multipart
                val boundary = "drive_boundary_${System.currentTimeMillis()}"
                val metadataJson = if (existingId == null)
                    """{"name":"$fileName","parents":["appDataFolder"]}"""
                else
                    "{}"

                val uploadUrl = if (existingId != null)
                    URL("https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=multipart")
                else
                    URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")

                val uploadConn = uploadUrl.openConnection() as java.net.HttpURLConnection
                uploadConn.requestMethod = if (existingId != null) "PATCH" else "POST"
                uploadConn.setRequestProperty("Authorization", "Bearer $token")
                uploadConn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
                uploadConn.doOutput = true

                val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadataJson\r\n--$boundary\r\nContent-Type: application/json\r\n\r\n$json\r\n--$boundary--\r\n"
                uploadConn.outputStream.write(body.toByteArray(Charsets.UTF_8))

                val responseCode = uploadConn.responseCode
                val responseBody = if (responseCode in 200..299)
                    uploadConn.inputStream.bufferedReader().readText()
                else
                    uploadConn.errorStream?.bufferedReader()?.readText() ?: ""
                uploadConn.disconnect()

                android.util.Log.d("GoogleDrive", "Upload response $responseCode: $responseBody")

                if (responseCode == 401) {
                    android.util.Log.w("GoogleDrive", "401 received, session expired")
                    promise.reject("DRIVE_ERROR", "Session expired, please sign in again")
                } else if (responseCode == 403 && responseBody.contains("storageQuotaExceeded")) {
                    android.util.Log.w("GoogleDrive", "Storage quota exceeded")
                    promise.reject("QUOTA_ERROR", "storageQuotaExceeded")
                } else if (responseCode in 200..299) {
                    val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                    prefsManager.setLastBackupDate(isoDate)
                    promise.resolve(isoDate)
                } else {
                    promise.reject("DRIVE_ERROR", "Upload failed ($responseCode): $responseBody")
                }
            } catch (e: Exception) {
                android.util.Log.e("GoogleDrive", "backupToDrive failed", e)
                promise.reject("DRIVE_ERROR", e.message ?: "Unknown error")
            }
        }.start()
    }

    @ReactMethod
    fun checkDriveBackup(promise: Promise) {
        Thread {
            try {
                val token = try {
                    getAuthToken()
                } catch (e: Exception) {
                    promise.resolve(false); return@Thread
                }
                val encodedQuery = URLEncoder.encode("name='stepjourney_backup.json'", "UTF-8")
                val listConn = URL(
                    "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=$encodedQuery&fields=files(id)"
                ).openConnection() as java.net.HttpURLConnection
                listConn.setRequestProperty("Authorization", "Bearer $token")
                val listCode = listConn.responseCode
                val listText = if (listCode in 200..299) listConn.inputStream.bufferedReader().readText() else ""
                listConn.disconnect()
                val filesArray = JSONObject(listText).optJSONArray("files")
                promise.resolve(filesArray != null && filesArray.length() > 0)
            } catch (e: Exception) {
                android.util.Log.w("GoogleDrive", "checkDriveBackup: ${e.message}")
                promise.resolve(false)
            }
        }.start()
    }

    @ReactMethod
    fun restoreFromDrive(promise: Promise) {
        Thread {
            try {
                val token = getAuthToken()
                val encodedQuery = URLEncoder.encode("name='stepjourney_backup.json'", "UTF-8")
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
                val fileId = JSONObject(listText)
                    .optJSONArray("files")
                    ?.takeIf { it.length() > 0 }
                    ?.getJSONObject(0)
                    ?.getString("id")
                    ?: throw Exception("NO_BACKUP")
                val dlConn = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                    .openConnection() as java.net.HttpURLConnection
                dlConn.setRequestProperty("Authorization", "Bearer $token")
                val dlCode = dlConn.responseCode
                val content = if (dlCode in 200..299)
                    dlConn.inputStream.bufferedReader().readText()
                else {
                    val err = dlConn.errorStream?.bufferedReader()?.readText() ?: ""
                    dlConn.disconnect()
                    throw Exception("DOWNLOAD_ERROR ($dlCode): $err")
                }
                dlConn.disconnect()
                restoreFromJson(content)
                promise.resolve(null)
            } catch (e: Exception) {
                android.util.Log.e("GoogleDrive", "restoreFromDrive failed", e)
                promise.reject("RESTORE_ERROR", e.message ?: "Unknown error")
            }
        }.start()
    }

    @ReactMethod
    fun importBackup(promise: Promise) {
        val activity = reactContext.currentActivity
        if (activity == null) {
            promise.reject("NO_ACTIVITY", "No active activity")
            return
        }
        importPromise = promise
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        activity.startActivityForResult(intent, IMPORT_REQUEST_CODE)
    }

    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            IMPORT_REQUEST_CODE -> {
                val promise = importPromise ?: return
                importPromise = null

                if (resultCode != Activity.RESULT_OK || data?.data == null) {
                    promise.reject("CANCELLED", "User cancelled file selection")
                    return
                }

                try {
                    val uri = data.data!!
                    val content = reactContext.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.readText()
                        ?: throw Exception("Could not read file")
                    restoreFromJson(content)
                    promise.resolve(null)
                } catch (e: Exception) {
                    promise.reject("IMPORT_ERROR", e.message ?: "Unknown error")
                }
            }
            GOOGLE_SIGN_IN_REQUEST_CODE -> {
                // Resultado del selector de cuenta (AccountManager)
                android.util.Log.d("GoogleSignIn", "Account picker result: resultCode=$resultCode")
                val promise = googleSignInPromise ?: run {
                    android.util.Log.w("GoogleSignIn", "Promise was null, ignoring result")
                    return
                }

                if (resultCode != Activity.RESULT_OK) {
                    googleSignInPromise = null
                    promise.reject("CANCELLED", "User cancelled sign-in")
                    return
                }

                val email = data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME) ?: run {
                    googleSignInPromise = null
                    promise.reject("SIGN_IN_ERROR", "Could not get account name")
                    return
                }

                pendingAuthEmail = email
                val authRequest = AuthorizationRequest.Builder()
                    .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.appdata")))
                    .setAccount(android.accounts.Account(email, "com.google"))
                    .build()
                Identity.getAuthorizationClient(activity)
                    .authorize(authRequest)
                    .addOnSuccessListener { authResult ->
                        if (authResult.hasResolution()) {
                            try {
                                activity.startIntentSenderForResult(
                                    authResult.pendingIntent!!.intentSender,
                                    GOOGLE_AUTH_REQUEST_CODE, null, 0, 0, 0
                                )
                            } catch (e: Exception) {
                                googleSignInPromise = null
                                pendingAuthEmail = null
                                promise.reject("SIGN_IN_ERROR", e.message ?: "Failed to start authorization")
                            }
                        } else {
                            googleSignInPromise = null
                            pendingAuthEmail = null
                            saveGoogleAccount(email, email)
                            android.util.Log.d("GoogleSignIn", "Authorization OK (no consent needed): $email")
                            val map = Arguments.createMap().apply {
                                putString("email", email)
                                putString("displayName", email)
                            }
                            promise.resolve(map)
                        }
                    }
                    .addOnFailureListener { e ->
                        googleSignInPromise = null
                        pendingAuthEmail = null
                        promise.reject("SIGN_IN_ERROR", e.message ?: "Authorization failed")
                    }
            }
            GOOGLE_AUTH_REQUEST_CODE -> {
                // Resultado del consentimiento de Drive
                android.util.Log.d("GoogleSignIn", "Drive auth result: resultCode=$resultCode")
                val promise = googleSignInPromise ?: run {
                    android.util.Log.w("GoogleSignIn", "Promise was null, ignoring result")
                    return
                }
                googleSignInPromise = null
                val email = pendingAuthEmail ?: ""
                pendingAuthEmail = null

                try {
                    Identity.getAuthorizationClient(activity)
                        .getAuthorizationResultFromIntent(data)
                    saveGoogleAccount(email, email)
                    android.util.Log.d("GoogleSignIn", "Drive authorization OK: $email")
                    val map = Arguments.createMap().apply {
                        putString("email", email)
                        putString("displayName", email)
                    }
                    promise.resolve(map)
                } catch (e: ApiException) {
                    android.util.Log.w("GoogleSignIn", "ApiException statusCode=${e.statusCode}")
                    if (e.statusCode == 16) {
                        promise.reject("CANCELLED", "User cancelled authorization")
                    } else {
                        promise.reject("SIGN_IN_ERROR", "Authorization failed with code ${e.statusCode}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GoogleSignIn", "Exception in Drive auth result", e)
                    promise.reject("SIGN_IN_ERROR", e.message ?: "Unknown error")
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {}

    private fun restoreFromJson(json: String) {
        val root = JSONObject(json)

        val configArray = root.optJSONArray("config")
        if (configArray != null) {
            for (i in 0 until configArray.length()) {
                val item = configArray.getJSONObject(i)
                configRepo.set(item.getString("key"), item.getString("value"))
            }
        }

        val dailyArray = root.optJSONArray("daily_history")
        if (dailyArray != null) {
            db.beginTransaction()
            try {
                for (i in 0 until dailyArray.length()) {
                    val item = dailyArray.getJSONObject(i)
                    val values = ContentValues().apply {
                        put("date", item.getString("date"))
                        put("steps", item.getInt("steps"))
                        put("offset", item.optInt("offset", 0))
                        put("goal", item.optInt("goal", 10000))
                    }
                    db.insertWithOnConflict(
                        "daily_history", null, values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        // Journeys: merge con CONFLICT_IGNORE → los viajes locales existentes no se sobreescriben,
        // y los viajes del backup que no existen localmente se añaden.
        val journeysArray = root.optJSONArray("journeys")
        if (journeysArray != null) {
            db.beginTransaction()
            try {
                for (i in 0 until journeysArray.length()) {
                    val item = journeysArray.getJSONObject(i)
                    val values = ContentValues().apply {
                        put("journeyId",          item.optString("journeyId"))
                        put("status",             item.optString("status"))
                        put("destination_name",   item.optString("destination_name"))
                        put("destination_lat",    item.optDouble("destination_lat", 0.0))
                        put("destination_lon",    item.optDouble("destination_lon", 0.0))
                        put("destination_address",item.optString("destination_address"))
                        put("origin_name",        item.optString("origin_name"))
                        put("origin_lat",         item.optDouble("origin_lat", 0.0))
                        put("origin_lon",         item.optDouble("origin_lon", 0.0))
                        put("origin_address",     item.optString("origin_address"))
                        put("route_coords",       item.optString("route_coords"))
                        put("total_distance_km",  item.optDouble("total_distance_km", 0.0))
                        put("checkpoints",        item.optString("checkpoints"))
                        put("created_at",         item.optString("created_at"))
                        put("started_at",         item.optString("started_at"))
                        put("completed_at",       item.optString("completed_at"))
                    }
                    db.insertWithOnConflict("journeys", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        // Journey daily log: merge con CONFLICT_IGNORE → el progreso local tiene preferencia.
        val journeyLogArray = root.optJSONArray("journey_daily_log")
        if (journeyLogArray != null) {
            db.beginTransaction()
            try {
                for (i in 0 until journeyLogArray.length()) {
                    val item = journeyLogArray.getJSONObject(i)
                    val values = ContentValues().apply {
                        put("id",                          item.optString("id"))
                        put("journeyId",                   item.optString("journeyId"))
                        put("date",                        item.optString("date"))
                        put("trip_day_number",             item.optInt("trip_day_number", 0))
                        put("is_paused",                   item.optInt("is_paused", 0))
                        put("current_checkpoint",          item.optInt("current_checkpoint", 0))
                        put("current_location_name",       item.optString("current_location_name"))
                        put("current_location_lat",        item.optDouble("current_location_lat", 0.0))
                        put("current_location_lon",        item.optDouble("current_location_lon", 0.0))
                        put("total_walked_km_in_journey",  item.optDouble("total_walked_km_in_journey", 0.0))
                        put("progress_percent",            item.optDouble("progress_percent", 0.0))
                        put("created_at",                  item.optString("created_at"))
                    }
                    db.insertWithOnConflict("journey_daily_log", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    @ReactMethod
    fun scheduleBackgroundSync() {
        val request = PeriodicWorkRequestBuilder<StepSyncWorker>(15, TimeUnit.MINUTES)
            .addTag("step_sync")
            .build()

        WorkManager.getInstance(reactContext).enqueueUniquePeriodicWork(
            "step_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun getDayKey(day: DayOfWeek): String {
        return when (day) {
            DayOfWeek.MONDAY -> "mon"
            DayOfWeek.TUESDAY -> "tue"
            DayOfWeek.WEDNESDAY -> "wed"
            DayOfWeek.THURSDAY -> "thu"
            DayOfWeek.FRIDAY -> "fri"
            DayOfWeek.SATURDAY -> "sat"
            DayOfWeek.SUNDAY -> "sun"
        }
    }

    private fun parseBadgeNotificationPayloads(rawJson: String): List<BadgeNotificationPayload> {
        if (rawJson.isBlank()) {
            return emptyList()
        }

        val jsonArray = JSONArray(rawJson)
        val payloads = mutableListOf<BadgeNotificationPayload>()

        for (index in 0 until jsonArray.length()) {
            val item = jsonArray.optJSONObject(index) ?: continue
            val key = item.optString("key").trim()
            if (key.isEmpty()) {
                continue
            }

            payloads.add(
                BadgeNotificationPayload(
                    key = key,
                    title = item.optString("title").trim(),
                    description = item.optString("description").trim(),
                )
            )
        }

        return payloads.distinctBy { it.key }
    }

    private fun canPostBadgeNotifications(): Boolean {
        if (!NotificationManagerCompat.from(reactContext).areNotificationsEnabled()) {
            return false
        }

        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                reactContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createBadgeNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val notificationManager = reactContext.getSystemService(NotificationManager::class.java)
            ?: return

        if (notificationManager.getNotificationChannel(BADGE_CHANNEL_ID) != null) {
            return
        }

        val ns = NotificationStrings.forLanguage(prefsManager.getLanguage())
        val channelName = ns.channelBadges
        val channelDescription = ns.channelBadgesDesc

        notificationManager.createNotificationChannel(
            NotificationChannel(
                BADGE_CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = channelDescription
                setShowBadge(true)
            },
        )
    }

    private fun showBadgeUnlockNotification(badge: BadgeNotificationPayload): Boolean {
        return try {
            val ns = NotificationStrings.forLanguage(prefsManager.getLanguage())
            val contentTitle = ns.newBadgeUnlocked
            val contentText = badge.title.ifBlank { ns.earnedBadge }
            val expandedText = badge.description.ifBlank { contentText }

            val launchIntent = reactContext.packageManager
                .getLaunchIntentForPackage(reactContext.packageName)
                ?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }

            val contentIntent = launchIntent?.let {
                PendingIntent.getActivity(
                    reactContext,
                    BADGE_NOTIFICATION_ID_BASE + badge.key.hashCode(),
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

            val notification = NotificationCompat.Builder(reactContext, BADGE_CHANNEL_ID)
                .setSmallIcon(com.steptracker.R.drawable.ic_notif_journey)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .apply {
                    if (contentIntent != null) {
                        setContentIntent(contentIntent)
                    }
                }
                .build()

            NotificationManagerCompat.from(reactContext).notify(
                BADGE_NOTIFICATION_ID_BASE + Math.abs(badge.key.hashCode()),
                notification,
            )
            true
        } catch (error: Exception) {
            android.util.Log.w("StepTrackerModule", "Unable to post badge notification for ${badge.key}", error)
            false
        }
    }

    private fun computeDistance(steps: Int): Pair<Double, String> {
        val km = steps * (METERS_PER_STEP / 1000.0)
        return when (prefsManager.getDistanceUnit()) {
            "miles" -> km * KM_TO_MILES to "mi"
            else -> km to "km"
        }
    }

    private fun computeEnergy(steps: Int): Pair<Double, String> {
        val kcal = steps * KCAL_PER_STEP
        return when (prefsManager.getEnergyUnit()) {
            "kj" -> kcal * KCAL_TO_KJ to "kJ"
            else -> kcal to "kcal"
        }
    }

    private fun getDistanceUnitLabel(): String {
        return when (prefsManager.getDistanceUnit()) {
            "miles" -> "mi"
            else -> "km"
        }
    }

    private fun getEnergyUnitLabel(): String {
        return when (prefsManager.getEnergyUnit()) {
            "kj" -> "kJ"
            else -> "kcal"
        }
    }

    private fun convertDistanceFromKm(km: Double): Double {
        return when (prefsManager.getDistanceUnit()) {
            "miles" -> km * KM_TO_MILES
            else -> km
        }
    }

    private fun convertEnergyFromKcal(kcal: Double): Double {
        return when (prefsManager.getEnergyUnit()) {
            "kj" -> kcal * KCAL_TO_KJ
            else -> kcal
        }
    }

    private fun convertSpeedFromKmh(kmh: Double): Double {
        return when (prefsManager.getDistanceUnit()) {
            "miles" -> kmh * KM_TO_MILES
            else -> kmh
        }
    }

    private fun putPrimitiveValue(map: WritableMap, key: String, value: Any?) {
        when (value) {
            null -> map.putNull(key)
            is String -> map.putString(key, value)
            is Boolean -> map.putBoolean(key, value)
            is Int -> map.putInt(key, value)
            is Long -> map.putDouble(key, value.toDouble())
            is Float -> map.putDouble(key, value.toDouble())
            is Double -> map.putDouble(key, value)
            is Number -> map.putDouble(key, value.toDouble())
        }
    }

    private fun addDisplayDistanceToCheckpoints(checkpointsJson: String): String {
        return try {
            val checkpoints = JSONArray(checkpointsJson)
            for (index in 0 until checkpoints.length()) {
                val checkpoint = checkpoints.optJSONObject(index) ?: continue
                val rawKm = checkpoint.optDouble("km", 0.0)
                checkpoint.put("display_distance", convertDistanceFromKm(rawKm))
            }
            checkpoints.toString()
        } catch (_: Exception) {
            checkpointsJson
        }
    }

    private fun convertJourneyDays(days: Any?): WritableArray {
        val result = Arguments.createArray()

        if (days !is List<*>) {
            return result
        }

        days.forEach { dayValue ->
            if (dayValue !is Map<*, *>) {
                return@forEach
            }

            val dayMap = Arguments.createMap()
            dayValue.forEach { (rawKey, rawValue) ->
                val key = rawKey as? String ?: return@forEach
                when (key) {
                    "distance" -> dayMap.putDouble(key, convertDistanceFromKm((rawValue as? Number)?.toDouble() ?: 0.0))
                    "calories" -> dayMap.putDouble(key, convertEnergyFromKcal((rawValue as? Number)?.toDouble() ?: 0.0))
                    else -> putPrimitiveValue(dayMap, key, rawValue)
                }
            }
            result.pushMap(dayMap)
        }

        return result
    }

    private fun applyPreferencesToJourney(journey: ReadableMap): WritableMap {
        val resultMap = Arguments.createMap()
        var totalDistanceKm = 0.0
        
        journey.toHashMap().forEach { (key, value) ->
            when (key) {
                "total_distance_km" -> {
                    totalDistanceKm = (value as? Number)?.toDouble() ?: 0.0
                    resultMap.putDouble("total_distance_km", totalDistanceKm)
                }
                "checkpoints" -> {
                    val checkpointsJson = value as? String ?: "[]"
                    resultMap.putString("checkpoints", addDisplayDistanceToCheckpoints(checkpointsJson))
                }
                else -> {
                    putPrimitiveValue(resultMap, key, value)
                }
            }
        }

        resultMap.putDouble("display_total_distance", convertDistanceFromKm(totalDistanceKm))
        resultMap.putString("distance_unit", getDistanceUnitLabel())

        return resultMap
    }

    private fun applyPreferencesToDailyLog(log: ReadableMap): WritableMap {
        val resultMap = Arguments.createMap()
        var totalWalkedKm = 0.0
        
        log.toHashMap().forEach { (key, value) ->
            when (key) {
                "total_walked_km_in_journey" -> {
                    totalWalkedKm = (value as? Number)?.toDouble() ?: 0.0
                    resultMap.putDouble("total_walked_km_in_journey", totalWalkedKm)
                }
                else -> {
                    putPrimitiveValue(resultMap, key, value)
                }
            }
        }

        resultMap.putDouble(
            "display_total_walked_km_in_journey",
            convertDistanceFromKm(totalWalkedKm),
        )
        resultMap.putString("distance_unit", getDistanceUnitLabel())

        return resultMap
    }

    private fun applyPreferencesToJourneyStatsSummary(summary: ReadableMap): WritableMap {
        val resultMap = Arguments.createMap()
        var totalWalkedKm = 0.0
        var todayWalkedKm = 0.0

        summary.toHashMap().forEach { (key, value) ->
            when (key) {
                "total_walked_km" -> {
                    totalWalkedKm = (value as? Number)?.toDouble() ?: 0.0
                    resultMap.putDouble(key, totalWalkedKm)
                }
                "today_walked_km" -> {
                    todayWalkedKm = (value as? Number)?.toDouble() ?: 0.0
                    resultMap.putDouble(key, todayWalkedKm)
                }
                else -> {
                    putPrimitiveValue(resultMap, key, value)
                }
            }
        }

        resultMap.putDouble("display_total_walked_km", convertDistanceFromKm(totalWalkedKm))
        resultMap.putDouble("display_today_walked_km", convertDistanceFromKm(todayWalkedKm))
        resultMap.putString("distance_unit", getDistanceUnitLabel())

        return resultMap
    }

    private fun applyPreferencesToJourneyPeriodStats(stats: ReadableMap): WritableMap {
        val resultMap = Arguments.createMap()

        stats.toHashMap().forEach { (key, value) ->
            when (key) {
                "totalDistance" -> resultMap.putDouble(key, convertDistanceFromKm((value as? Number)?.toDouble() ?: 0.0))
                "totalCalories" -> resultMap.putDouble(key, convertEnergyFromKcal((value as? Number)?.toDouble() ?: 0.0))
                "days" -> resultMap.putArray(key, convertJourneyDays(value))
                else -> putPrimitiveValue(resultMap, key, value)
            }
        }

        resultMap.putString("distanceUnit", getDistanceUnitLabel())
        resultMap.putString("energyUnit", getEnergyUnitLabel())

        return resultMap
    }

    private fun applyPreferencesToJourneyPerformanceStats(stats: ReadableMap): WritableMap {
        val resultMap = Arguments.createMap()

        stats.toHashMap().forEach { (key, value) ->
            when (key) {
                "todayWalkedKm" -> resultMap.putDouble(key, convertDistanceFromKm((value as? Number)?.toDouble() ?: 0.0))
                "calories" -> resultMap.putDouble(key, convertEnergyFromKcal((value as? Number)?.toDouble() ?: 0.0))
                "averageSpeedKmh" -> resultMap.putDouble(key, convertSpeedFromKmh((value as? Number)?.toDouble() ?: 0.0))
                else -> putPrimitiveValue(resultMap, key, value)
            }
        }

        resultMap.putString("distanceUnit", getDistanceUnitLabel())
        resultMap.putString("energyUnit", getEnergyUnitLabel())
        resultMap.putString("speedUnit", if (getDistanceUnitLabel() == "mi") "mi/h" else "km/h")

        return resultMap
    }

    // ========================================================================
    // JOURNEY METHODS
    // ========================================================================

    @ReactMethod
    fun createJourney(
        destinationName: String,
        destinationLat: Double,
        destinationLon: Double,
        destinationAddress: String,
        originName: String,
        originLat: Double,
        originLon: Double,
        originAddress: String,
        routeCoordsJson: String,
        totalDistanceKm: Double,
        checkpointsJson: String,
        promise: Promise
    ) {
        try {
            val journeyId = UUID.randomUUID().toString()
            android.util.Log.d("StepTrackerModule", "Creating journey: $journeyId, dest: $destinationName, origin: $originName")
            
            val success = journeyRepo.createJourney(
                journeyId, destinationName, destinationLat, destinationLon, destinationAddress,
                originName, originLat, originLon, originAddress,
                routeCoordsJson, totalDistanceKm, checkpointsJson
            )

            if (success) {
                refreshForegroundNotification()
                val result = Arguments.createMap().apply {
                    putString("journeyId", journeyId)
                    putString("createdAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date()))
                }
                android.util.Log.d("StepTrackerModule", "Journey created successfully: $journeyId")
                promise.resolve(result)
            } else {
                android.util.Log.e("StepTrackerModule", "Failed to create journey: $journeyId (repository returned false)")
                promise.reject("CREATE_JOURNEY_ERROR", "Failed to create journey (repository error)")
            }
        } catch (e: Exception) {
            android.util.Log.e("StepTrackerModule", "Exception creating journey: ${e.message}", e)
            promise.reject("CREATE_JOURNEY_ERROR", e)
        }
    }

    @ReactMethod
    fun getJourney(journeyId: String, promise: Promise) {
        try {
            val journey = journeyRepo.getJourney(journeyId)
            if (journey != null) {
                // Apply user preferences (km/miles conversion)
                val result = applyPreferencesToJourney(journey)
                promise.resolve(result)
            } else {
                promise.reject("JOURNEY_NOT_FOUND", "Journey not found: $journeyId")
            }
        } catch (e: Exception) {
            promise.reject("GET_JOURNEY_ERROR", e)
        }
    }

    @ReactMethod
    fun updateJourneyProgress(
        journeyId: String,
        status: String,
        currentCheckpoint: Int?,
        currentLocationName: String?,
        currentLocationLat: Double?,
        currentLocationLon: Double?,
        progressPercent: Double?,
        walkedKmInJourney: Double?,
        promise: Promise
    ) {
        try {
            val success = journeyRepo.updateJourneyProgress(
                journeyId, status, currentCheckpoint, currentLocationName,
                currentLocationLat, currentLocationLon, progressPercent, walkedKmInJourney
            )

            if (success) {
                refreshForegroundNotification()
                val journey = journeyRepo.getJourney(journeyId)
                if (journey != null) {
                    // Apply user preferences
                    val result = applyPreferencesToJourney(journey)
                    promise.resolve(result)
                } else {
                    promise.reject("UPDATE_JOURNEY_ERROR", "Journey not found after update")
                }
            } else {
                promise.reject("UPDATE_JOURNEY_ERROR", "Failed to update journey")
            }
        } catch (e: Exception) {
            promise.reject("UPDATE_JOURNEY_ERROR", e)
        }
    }

    @ReactMethod
    fun pauseJourney(journeyId: String, promise: Promise) {
        try {
            val success = journeyRepo.updateJourneyProgress(
                journeyId, "paused", null, null, null, null, null, null
            )

            if (success) {
                refreshForegroundNotification()
                promise.resolve(Arguments.createMap().apply {
                    putString("status", "paused")
                })
            } else {
                promise.reject("PAUSE_JOURNEY_ERROR", "Failed to pause journey")
            }
        } catch (e: Exception) {
            promise.reject("PAUSE_JOURNEY_ERROR", e)
        }
    }

    @ReactMethod
    fun completeJourney(journeyId: String, promise: Promise) {
        try {
            val success = journeyRepo.updateJourneyProgress(
                journeyId, "completed", null, null, null, null, null, null
            )

            if (success) {
                refreshForegroundNotification()
                promise.resolve(Arguments.createMap().apply {
                    putString("status", "completed")
                })
            } else {
                promise.reject("COMPLETE_JOURNEY_ERROR", "Failed to complete journey")
            }
        } catch (e: Exception) {
            promise.reject("COMPLETE_JOURNEY_ERROR", e)
        }
    }

    @ReactMethod
    fun getJourneyDailyLog(journeyId: String, date: String?, promise: Promise) {
        try {
            val log = journeyRepo.getJourneyDailyLog(journeyId, date)
            if (log != null) {
                // Apply user preferences
                val result = applyPreferencesToDailyLog(log)
                promise.resolve(result)
            } else {
                promise.reject("LOG_NOT_FOUND", "Daily log not found")
            }
        } catch (e: Exception) {
            promise.reject("GET_LOG_ERROR", e)
        }
    }

    @ReactMethod
    fun getJourneyStatsSummary(journeyId: String, date: String?, promise: Promise) {
        try {
            val summary = journeyRepo.getJourneyStatsSummary(journeyId, date)
            val result = applyPreferencesToJourneyStatsSummary(summary)
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("GET_JOURNEY_STATS_SUMMARY_ERROR", e)
        }
    }

    @ReactMethod
    fun syncJourneyDailyLog(journeyId: String, promise: Promise) {
        try {
            val today = currentDate()
            val syncedLog = journeyRepo.ensureJourneyDailyLog(journeyId, today)
            if (syncedLog != null) {
                refreshForegroundNotification()
                promise.resolve(applyPreferencesToDailyLog(syncedLog))
            } else {
                promise.reject("SYNC_JOURNEY_DAILY_LOG_ERROR", "Daily log not found after sync")
            }
        } catch (e: Exception) {
            promise.reject("SYNC_JOURNEY_DAILY_LOG_ERROR", e)
        }
    }

    @ReactMethod
    fun saveJourneyDailyLog(
        journeyId: String,
        date: String,
        tripDayNumber: Int,
        isPaused: Boolean,
        currentCheckpoint: Int,
        currentLocationName: String,
        currentLocationLat: Double,
        currentLocationLon: Double,
        totalWalkedKm: Double,
        progressPercent: Double,
        promise: Promise
    ) {
        try {
            val success = journeyRepo.saveJourneyDailyLog(
                journeyId, date, tripDayNumber, isPaused, currentCheckpoint,
                currentLocationName, currentLocationLat, currentLocationLon,
                totalWalkedKm, progressPercent
            )

            if (success) {
                refreshForegroundNotification()
                val log = journeyRepo.getJourneyDailyLog(journeyId, date)
                if (log != null) {
                    // Apply user preferences
                    val result = applyPreferencesToDailyLog(log)
                    promise.resolve(result)
                } else {
                    promise.resolve(Arguments.createMap().apply {
                        putString("savedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date()))
                    })
                }
            } else {
                promise.reject("SAVE_LOG_ERROR", "Failed to save daily log")
            }
        } catch (e: Exception) {
            promise.reject("SAVE_LOG_ERROR", e)
        }
    }

    @ReactMethod
    fun getAllJourneys(status: String?, promise: Promise) {
        try {
            val journeys = journeyRepo.getAllJourneys(status)
            
            // Apply user preferences to each journey
            val resultArray = Arguments.createArray()
            for (i in 0 until journeys.size()) {
                val journey = journeys.getMap(i)
                if (journey != null) {
                    val result = applyPreferencesToJourney(journey)
                    resultArray.pushMap(result)
                }
            }
            
            promise.resolve(resultArray)
        } catch (e: Exception) {
            promise.reject("GET_ALL_JOURNEYS_ERROR", e)
        }
    }

    @ReactMethod
    fun deleteJourney(journeyId: String, promise: Promise) {
        try {
            val success = journeyRepo.deleteJourney(journeyId)
            if (success) {
                refreshForegroundNotification()
                promise.resolve(Arguments.createMap().apply {
                    putBoolean("deleted", true)
                })
            } else {
                promise.reject("DELETE_JOURNEY_ERROR", "Failed to delete journey")
            }
        } catch (e: Exception) {
            promise.reject("DELETE_JOURNEY_ERROR", e)
        }
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
