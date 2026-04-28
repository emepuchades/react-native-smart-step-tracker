package com.steptracker.bridge

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.facebook.react.bridge.*
import com.steptracker.StepSyncWorker
import com.steptracker.StepTrackerService
import com.steptracker.data.steps.ConfigRepository
import com.steptracker.data.steps.JourneyRepository
import com.steptracker.data.steps.StepHistoryRepository
import com.steptracker.data.steps.StepStatsRepository
import com.steptracker.data.steps.StepsDatabaseHelper
import com.steptracker.data.steps.UserPreferencesManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

class StepTrackerModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "StepTrackerModule"

    companion object {
        private const val METERS_PER_STEP = 0.8
        private const val KM_TO_MILES = 0.621371
        private const val KCAL_PER_STEP = 0.04
        private const val KCAL_TO_KJ = 4.184
    }

    private val dbHelper = StepsDatabaseHelper(reactContext)
    private val db = dbHelper.writableDatabase

    private val configRepo = ConfigRepository(db)
    private val prefsManager = UserPreferencesManager(configRepo)
    private val historyRepo = StepHistoryRepository(db, configRepo)
    private val statsRepo = StepStatsRepository(db, prefsManager, historyRepo)
    private val journeyRepo = JourneyRepository(db)

    init {
        android.util.Log.d("StepTrackerModule", "Module initialized, DB version: ${dbHelper.readableDatabase.version}")
        android.util.Log.d("StepTrackerModule", "Database path: ${reactContext.getDatabasePath("steps.db").absolutePath}")
        
        // Ensure journey tables exist
        ensureJourneyTablesExist()
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

    private fun computeWeekStartDate(today: LocalDate, start: String): LocalDate {
        val weekStartDay = when (start.lowercase()) {
            "monday" -> DayOfWeek.MONDAY
            "sunday" -> DayOfWeek.SUNDAY
            "saturday" -> DayOfWeek.SATURDAY
            else -> DayOfWeek.MONDAY
        }
        return today.with(TemporalAdjusters.previousOrSame(weekStartDay))
    }


    @ReactMethod
    fun startTracking() {
        val intent = Intent(reactContext, StepTrackerService::class.java)
        ContextCompat.startForegroundService(reactContext, intent)
    }

    @ReactMethod
    fun stopTracking() {
        val intent = Intent(reactContext, StepTrackerService::class.java)
        reactContext.stopService(intent)
    }

    @ReactMethod
    fun ensureServiceRunning() {
        val intent = Intent().apply {
            setClassName(reactContext, "com.steptracker.StepTrackerService")
            action = "RESTART_SERVICE"
        }
        ContextCompat.startForegroundService(reactContext, intent)
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
    fun getWeeklyStatsJourney(journeyId: String, lang: String, offset: Int, promise: Promise) {
        try {
            promise.resolve(journeyRepo.getWeeklyStatsJourney(journeyId, offset, historyRepo.getDailyGoal()))
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
    fun getMonthlyStatsJourney(journeyId: String, lang: String, offset: Int, promise: Promise) {
        try {
            promise.resolve(journeyRepo.getMonthlyStatsJourney(journeyId, offset, historyRepo.getDailyGoal()))
        } catch (e: Exception) {
            promise.reject("GET_MONTHLY_JOURNEY_STATS_ERROR", e)
        }
    }

    @ReactMethod
    fun getPerformanceStatsJourney(journeyId: String, date: String?, promise: Promise) {
        try {
            promise.resolve(journeyRepo.getPerformanceStatsJourney(journeyId, date, historyRepo.getDailyGoal()))
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

    @ReactMethod fun setUserLanguage(lang: String) = prefsManager.setLanguage(lang)
    @ReactMethod fun setWeekStart(day: String) = prefsManager.setWeekStart(day)
    @ReactMethod fun setDistanceUnit(unit: String) = prefsManager.setDistanceUnit(unit)
    @ReactMethod fun setEnergyUnit(unit: String) = prefsManager.setEnergyUnit(unit)

    @ReactMethod
    fun getUserPreferences(promise: Promise) {
        try {
            promise.resolve(prefsManager.getUserPreferences())
        } catch (e: Exception) {
            promise.reject("ERROR_PREFS", e)
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

    // Convert distance from KM based on user preferences - returns only the value
    private fun convertDistanceFromKm(km: Double): Double {
        return when (prefsManager.getDistanceUnit()) {
            "miles" -> km * KM_TO_MILES
            else -> km
        }
    }

    // Convert energy from kcal based on user preferences - returns only the value
    private fun convertEnergyFromKcal(kcal: Double): Double {
        return when (prefsManager.getEnergyUnit()) {
            "kj" -> kcal * KCAL_TO_KJ
            else -> kcal
        }
    }

    // Apply user preferences to journey data - replace distance values only
    private fun applyPreferencesToJourney(journey: ReadableMap): WritableMap {
        val resultMap = Arguments.createMap()
        
        // Copy all existing fields
        journey.toHashMap().forEach { (key, value) ->
            when (key) {
                "total_distance_km" -> {
                    // Convert and replace only the value
                    val kmValue = (value as? Number)?.toDouble() ?: 0.0
                    resultMap.putDouble("total_distance_km", convertDistanceFromKm(kmValue))
                }
                else -> {
                    when (value) {
                        is String -> resultMap.putString(key, value)
                        is Double -> resultMap.putDouble(key, value)
                        is Int -> resultMap.putInt(key, value)
                        is Boolean -> resultMap.putBoolean(key, value)
                        else -> {}
                    }
                }
            }
        }

        return resultMap
    }

    // Apply user preferences to daily log - replace distance values only
    private fun applyPreferencesToDailyLog(log: ReadableMap): WritableMap {
        val resultMap = Arguments.createMap()
        
        // Copy all existing fields
        log.toHashMap().forEach { (key, value) ->
            when (key) {
                "total_walked_km_in_journey" -> {
                    // Convert and replace only the value
                    val kmValue = (value as? Number)?.toDouble() ?: 0.0
                    resultMap.putDouble("total_walked_km_in_journey", convertDistanceFromKm(kmValue))
                }
                else -> {
                    when (value) {
                        is String -> resultMap.putString(key, value)
                        is Double -> resultMap.putDouble(key, value)
                        is Int -> resultMap.putInt(key, value)
                        is Boolean -> resultMap.putBoolean(key, value)
                        else -> {}
                    }
                }
            }
        }

        return resultMap
    }

    private fun applyPreferencesToJourneyStatsSummary(summary: ReadableMap): WritableMap {
        val resultMap = Arguments.createMap()

        summary.toHashMap().forEach { (key, value) ->
            when (key) {
                "total_walked_km", "today_walked_km" -> {
                    val kmValue = (value as? Number)?.toDouble() ?: 0.0
                    resultMap.putDouble(key, convertDistanceFromKm(kmValue))
                }
                else -> {
                    when (value) {
                        is String -> resultMap.putString(key, value)
                        is Double -> resultMap.putDouble(key, value)
                        is Int -> resultMap.putInt(key, value)
                        is Boolean -> resultMap.putBoolean(key, value)
                        null -> resultMap.putNull(key)
                        else -> {}
                    }
                }
            }
        }

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
