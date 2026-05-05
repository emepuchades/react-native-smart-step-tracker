package com.steptracker.data.steps

import android.database.sqlite.SQLiteDatabase
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.*
import kotlin.math.ceil

class JourneyRepository(private val db: SQLiteDatabase) {

    private val configRepo = ConfigRepository(db)

    private fun calculateDistanceKm(steps: Int): Double = steps * 0.0008

    private fun calculateCaloriesKcal(steps: Int): Double = steps * 0.04

    private fun resolveReferenceDate(referenceDate: String?): LocalDate {
        return try {
            if (referenceDate.isNullOrBlank()) {
                LocalDate.now()
            } else {
                LocalDate.parse(referenceDate)
            }
        } catch (_: Exception) {
            LocalDate.now()
        }
    }

    private fun markJourneyCompleted(journeyId: String) {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date())
        db.execSQL(
            "UPDATE journeys SET status = 'completed', completed_at = ? WHERE journeyId = ? AND LOWER(TRIM(status)) != 'completed'",
            arrayOf(now, journeyId)
        )
    }

    private fun computeWeekStartShift(date: LocalDate): Int {
        return when ((configRepo.get("week_start") ?: "monday").lowercase(Locale.getDefault())) {
            "sunday" -> date.dayOfWeek.value % 7
            else -> date.dayOfWeek.value - 1
        }
    }

    private fun dayShort(date: LocalDate): String {
        return when (date.dayOfWeek.value) {
            1 -> "L"
            2 -> "M"
            3 -> "X"
            4 -> "J"
            5 -> "V"
            6 -> "S"
            else -> "D"
        }
    }

    private fun dayFull(date: LocalDate): String {
        return date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("es", "ES"))
    }

    private fun getCountedStepsForRange(journeyId: String, start: LocalDate, end: LocalDate): Int {
        var total = 0

        var current = start
        while (!current.isAfter(end)) {
            total += getCountedStepsForDate(journeyId, current.toString())
            current = current.plusDays(1)
        }

        return total
    }

    private fun getCountedStepsForDate(journeyId: String, date: String): Int {
        val currentCursor = db.rawQuery(
            """
            SELECT COALESCE(counted_steps, 0), total_walked_km_in_journey
            FROM journey_daily_log
            WHERE journeyId = ? AND date = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(journeyId, date)
        )

        if (!currentCursor.moveToFirst()) {
            currentCursor.close()
            return 0
        }

        val countedSteps = currentCursor.getInt(0)
        val totalWalkedKm = if (!currentCursor.isNull(1)) currentCursor.getDouble(1) else 0.0
        currentCursor.close()

        if (countedSteps > 0) {
            return countedSteps
        }

        if (totalWalkedKm <= 0.0) {
            return 0
        }

        val previousCursor = db.rawQuery(
            """
            SELECT total_walked_km_in_journey
            FROM journey_daily_log
            WHERE journeyId = ?
              AND date < ?
              AND total_walked_km_in_journey IS NOT NULL
            ORDER BY date DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(journeyId, date)
        )

        val previousTotalWalkedKm = if (previousCursor.moveToFirst() && !previousCursor.isNull(0)) {
            previousCursor.getDouble(0)
        } else {
            0.0
        }
        previousCursor.close()

        val derivedSteps = maxOf(Math.round(((totalWalkedKm - previousTotalWalkedKm) / 0.0008)).toInt(), 0)

        if (derivedSteps > 0) {
            try {
                db.execSQL(
                    "UPDATE journey_daily_log SET counted_steps = ? WHERE journeyId = ? AND date = ?",
                    arrayOf(derivedSteps, journeyId, date)
                )
            } catch (_: Exception) {
            }
        }

        return derivedSteps
    }

    private fun incrementJourneyCountedSteps(
        journeyId: String,
        date: String,
        hour: Int?,
        deltaSteps: Int,
    ) {
        if (deltaSteps <= 0) {
            return
        }

        db.execSQL(
            """
            UPDATE journey_daily_log
            SET counted_steps = COALESCE(counted_steps, 0) + ?
            WHERE journeyId = ? AND date = ?
            """.trimIndent(),
            arrayOf(deltaSteps, journeyId, date)
        )

        if (hour == null) {
            return
        }

        val cursor = db.rawQuery(
            "SELECT steps FROM journey_hourly_history WHERE journeyId = ? AND date = ? AND hour = ?",
            arrayOf(journeyId, date, hour.toString())
        )

        val exists: Boolean
        val existingSteps: Int
        if (cursor.moveToFirst()) {
            exists = true
            existingSteps = cursor.getInt(0)
        } else {
            exists = false
            existingSteps = 0
        }
        cursor.close()

        if (exists) {
            db.execSQL(
                "UPDATE journey_hourly_history SET steps = ? WHERE journeyId = ? AND date = ? AND hour = ?",
                arrayOf(existingSteps + deltaSteps, journeyId, date, hour)
            )
        } else {
            db.execSQL(
                "INSERT INTO journey_hourly_history(journeyId, date, hour, steps) VALUES (?, ?, ?, ?)",
                arrayOf(journeyId, date, hour, deltaSteps)
            )
        }
    }

    private fun syncTodayJourneyPauseState(
        journeyId: String,
        isPaused: Boolean,
        date: String? = null,
    ): WritableMap? {
        val queryDate = date ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val baseLog = ensureJourneyDailyLog(journeyId, queryDate) ?: return null

        val success = saveJourneyDailyLog(
            journeyId,
            queryDate,
            if (baseLog.hasKey("trip_day_number")) baseLog.getInt("trip_day_number") else 1,
            isPaused,
            if (baseLog.hasKey("current_checkpoint")) baseLog.getInt("current_checkpoint") else 0,
            if (baseLog.hasKey("current_location_name") && !baseLog.isNull("current_location_name")) {
                baseLog.getString("current_location_name") ?: ""
            } else {
                ""
            },
            if (baseLog.hasKey("current_location_lat") && !baseLog.isNull("current_location_lat")) {
                baseLog.getDouble("current_location_lat")
            } else {
                0.0
            },
            if (baseLog.hasKey("current_location_lon") && !baseLog.isNull("current_location_lon")) {
                baseLog.getDouble("current_location_lon")
            } else {
                0.0
            },
            if (baseLog.hasKey("total_walked_km_in_journey") && !baseLog.isNull("total_walked_km_in_journey")) {
                baseLog.getDouble("total_walked_km_in_journey")
            } else {
                0.0
            },
            if (baseLog.hasKey("progress_percent") && !baseLog.isNull("progress_percent")) {
                baseLog.getDouble("progress_percent")
            } else {
                0.0
            }
        )

        return if (success) getJourneyDailyLog(journeyId, queryDate) else null
    }

    // ========================================================================
    // CREATE JOURNEY
    // ========================================================================

    fun createJourney(
        journeyId: String,
        destinationName: String,
        destinationLat: Double,
        destinationLon: Double,
        destinationAddress: String,
        originName: String,
        originLat: Double,
        originLon: Double,
        originAddress: String,
        routeCoords: String, // JSON string
        totalDistanceKm: Double,
        checkpoints: String // JSON string
    ): Boolean {
        return try {
            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date())
            
            // Step 1: Pause all currently active journeys (only one active at a time)
            android.util.Log.d("JourneyRepository", "Pausing any active journeys...")
            val pauseStmt = db.compileStatement("UPDATE journeys SET status = 'paused' WHERE LOWER(TRIM(status)) = 'active'")
            pauseStmt.executeUpdateDelete()
            pauseStmt.close()
            android.util.Log.d("JourneyRepository", "Active journeys paused (if any existed)")
            
            // Step 2: Insert new journey with active status
            val sql = """
                INSERT INTO journeys(
                    journeyId, status, destination_name, destination_lat, destination_lon, 
                    destination_address, origin_name, origin_lat, origin_lon, origin_address,
                    route_coords, total_distance_km, checkpoints, created_at, started_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            
            val stmt = db.compileStatement(sql)
            stmt.bindString(1, journeyId)
            stmt.bindString(2, "active")
            stmt.bindString(3, destinationName)
            stmt.bindDouble(4, destinationLat)
            stmt.bindDouble(5, destinationLon)
            stmt.bindString(6, destinationAddress)
            stmt.bindString(7, originName)
            stmt.bindDouble(8, originLat)
            stmt.bindDouble(9, originLon)
            stmt.bindString(10, originAddress)
            stmt.bindString(11, routeCoords)
            stmt.bindDouble(12, totalDistanceKm)
            stmt.bindString(13, checkpoints)
            stmt.bindString(14, now)
            stmt.bindString(15, now)
            
            stmt.executeInsert()
            stmt.close()
            
            android.util.Log.d("JourneyRepository", "Journey created successfully: $journeyId with status=active")
            true
        } catch (e: Exception) {
            android.util.Log.e("JourneyRepository", "Error creating journey: ${e.message}", e)
            e.printStackTrace()
            false
        }
    }

    // ========================================================================
    // GET JOURNEY
    // ========================================================================

    fun getJourney(journeyId: String): WritableMap? {
        return try {
            val cursor = db.rawQuery(
                """
                SELECT journeyId, status, destination_name, destination_lat, destination_lon,
                       destination_address, origin_name, origin_lat, origin_lon, origin_address,
                       route_coords, total_distance_km, checkpoints, created_at, started_at, completed_at
                FROM journeys WHERE journeyId = ?
                """.trimIndent(),
                arrayOf(journeyId)
            )

            if (cursor.moveToFirst()) {
                val map = Arguments.createMap().apply {
                    putString("journeyId", cursor.getString(0))
                    putString("status", cursor.getString(1))
                    
                    putString("destination_name", cursor.getString(2))
                    putDouble("destination_lat", cursor.getDouble(3))
                    putDouble("destination_lon", cursor.getDouble(4))
                    putString("destination_address", cursor.getString(5))
                    
                    putString("origin_name", cursor.getString(6))
                    putDouble("origin_lat", cursor.getDouble(7))
                    putDouble("origin_lon", cursor.getDouble(8))
                    putString("origin_address", cursor.getString(9))
                    
                    putString("route_coords", cursor.getString(10))
                    putDouble("total_distance_km", cursor.getDouble(11))
                    putString("checkpoints", cursor.getString(12))
                    
                    putString("created_at", cursor.getString(13))
                    putString("started_at", cursor.getString(14))
                    putString("completed_at", cursor.getString(15) ?: "")
                }
                cursor.close()
                map
            } else {
                cursor.close()
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getActiveJourney(): WritableMap? {
        return try {
            val cursor = db.rawQuery(
                """
                SELECT journeyId
                FROM journeys
                WHERE LOWER(TRIM(status)) = 'active'
                ORDER BY started_at DESC
                LIMIT 1
                """.trimIndent(),
                null
            )

            val journey = if (cursor.moveToFirst()) {
                getJourney(cursor.getString(0))
            } else {
                null
            }

            cursor.close()
            journey
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ========================================================================
    // UPDATE JOURNEY STATUS & PROGRESS
    // ========================================================================

    fun updateJourneyProgress(
        journeyId: String,
        status: String,
        currentCheckpoint: Int?,
        currentLocationName: String?,
        currentLocationLat: Double?,
        currentLocationLon: Double?,
        progressPercent: Double?,
        walkedKmInJourney: Double?
    ): Boolean {
        return try {
            // Get current journey first
            val journey = getJourney(journeyId) ?: return false

            if (status == "active") {
                db.execSQL(
                    "UPDATE journeys SET status = 'paused' WHERE LOWER(TRIM(status)) = 'active' AND journeyId != ?",
                    arrayOf(journeyId)
                )
            }

            // Update main journey table
            var updateQuery = "UPDATE journeys SET status = ?"
            val params = mutableListOf<Any>(status)

            if (status == "completed") {
                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date())
                updateQuery += ", completed_at = ?"
                params.add(now)
            }

            updateQuery += " WHERE journeyId = ?"
            params.add(journeyId)

            db.execSQL(updateQuery, params.toTypedArray())

            if (status == "paused" || status == "active") {
                syncTodayJourneyPauseState(journeyId, status == "paused")
            }

            // If there's progress data, also update/insert journey_daily_log
            if (currentCheckpoint != null || currentLocationName != null) {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date())
                
                val logId = "${journeyId}_${today}"
                
                // Try to update first
                val cursor = db.rawQuery(
                    "SELECT id FROM journey_daily_log WHERE journeyId = ? AND date = ?",
                    arrayOf(journeyId, today)
                )
                
                if (cursor.moveToFirst()) {
                    // Update existing log
                    cursor.close()
                    db.execSQL(
                        """
                        UPDATE journey_daily_log SET 
                        current_checkpoint = COALESCE(?, current_checkpoint),
                        current_location_name = COALESCE(?, current_location_name),
                        current_location_lat = COALESCE(?, current_location_lat),
                        current_location_lon = COALESCE(?, current_location_lon),
                        progress_percent = COALESCE(?, progress_percent),
                        total_walked_km_in_journey = COALESCE(?, total_walked_km_in_journey)
                        WHERE journeyId = ? AND date = ?
                        """.trimIndent(),
                        arrayOf(
                            currentCheckpoint, currentLocationName, currentLocationLat, 
                            currentLocationLon, progressPercent, walkedKmInJourney,
                            journeyId, today
                        )
                    )
                } else {
                    // Insert new log
                    cursor.close()
                    db.execSQL(
                        """
                        INSERT INTO journey_daily_log(
                            id, journeyId, date, current_checkpoint, current_location_name,
                            current_location_lat, current_location_lon, progress_percent,
                            total_walked_km_in_journey, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf(
                            logId, journeyId, today, currentCheckpoint, currentLocationName,
                            currentLocationLat, currentLocationLon, progressPercent,
                            walkedKmInJourney, now
                        )
                    )
                }
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ========================================================================
    // GET JOURNEY DAILY LOG
    // ========================================================================

    fun getJourneyDailyLog(journeyId: String, date: String? = null): WritableMap? {
        return try {
            val queryDate = date ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            
            val cursor = db.rawQuery(
                """
                SELECT id, journeyId, date, trip_day_number, is_paused, current_checkpoint,
                       current_location_name, current_location_lat, current_location_lon,
                       counted_steps, total_walked_km_in_journey, progress_percent, created_at
                FROM journey_daily_log WHERE journeyId = ? AND date = ?
                """.trimIndent(),
                arrayOf(journeyId, queryDate)
            )

            if (cursor.moveToFirst()) {
                val map = Arguments.createMap().apply {
                    putString("id", cursor.getString(0))
                    putString("journeyId", cursor.getString(1))
                    putString("date", cursor.getString(2))
                    putInt("trip_day_number", cursor.getInt(3))
                    putBoolean("is_paused", cursor.getInt(4) == 1)
                    putInt("current_checkpoint", cursor.getInt(5))
                    putString("current_location_name", cursor.getString(6))
                    putDouble("current_location_lat", cursor.getDouble(7))
                    putDouble("current_location_lon", cursor.getDouble(8))
                    putInt("counted_steps", cursor.getInt(9))
                    putDouble("total_walked_km_in_journey", cursor.getDouble(10))
                    putDouble("progress_percent", cursor.getDouble(11))
                    putString("created_at", cursor.getString(12))
                }
                cursor.close()
                map
            } else {
                cursor.close()
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getJourneyStatsSummary(journeyId: String, date: String? = null): WritableMap {
        val queryDate = date ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val summary = Arguments.createMap().apply {
            putString("journeyId", journeyId)
            putString("summary_date", queryDate)
            putInt("logged_days", 0)
            putInt("active_days", 0)
            putInt("paused_days", 0)
            putBoolean("today_logged", false)
            putDouble("today_walked_km", 0.0)
            putDouble("total_walked_km", 0.0)
            putDouble("progress_percent", 0.0)
        }

        try {
            val countsCursor = db.rawQuery(
                """
                SELECT COUNT(*),
                       SUM(CASE WHEN is_paused = 0 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN is_paused = 1 THEN 1 ELSE 0 END)
                FROM journey_daily_log
                WHERE journeyId = ?
                """.trimIndent(),
                arrayOf(journeyId)
            )

            if (countsCursor.moveToFirst()) {
                summary.putInt("logged_days", countsCursor.getInt(0))
                summary.putInt("active_days", countsCursor.getInt(1))
                summary.putInt("paused_days", countsCursor.getInt(2))
            }
            countsCursor.close()

            val latestCursor = db.rawQuery(
                """
                SELECT date, trip_day_number, is_paused, current_checkpoint,
                       current_location_name, current_location_lat, current_location_lon,
                       total_walked_km_in_journey, progress_percent, created_at
                FROM journey_daily_log
                WHERE journeyId = ? AND date <= ?
                ORDER BY date DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(journeyId, queryDate)
            )

            if (latestCursor.moveToFirst()) {
                summary.putString("last_log_date", latestCursor.getString(0))

                if (latestCursor.isNull(1)) summary.putNull("trip_day_number")
                else summary.putInt("trip_day_number", latestCursor.getInt(1))

                summary.putBoolean("is_paused", latestCursor.getInt(2) == 1)

                if (latestCursor.isNull(3)) summary.putNull("current_checkpoint")
                else summary.putInt("current_checkpoint", latestCursor.getInt(3))

                if (latestCursor.isNull(4)) summary.putNull("current_location_name")
                else summary.putString("current_location_name", latestCursor.getString(4))

                if (latestCursor.isNull(5)) summary.putNull("current_location_lat")
                else summary.putDouble("current_location_lat", latestCursor.getDouble(5))

                if (latestCursor.isNull(6)) summary.putNull("current_location_lon")
                else summary.putDouble("current_location_lon", latestCursor.getDouble(6))

                if (latestCursor.isNull(7)) summary.putDouble("total_walked_km", 0.0)
                else summary.putDouble("total_walked_km", latestCursor.getDouble(7))

                if (latestCursor.isNull(8)) summary.putDouble("progress_percent", 0.0)
                else summary.putDouble("progress_percent", latestCursor.getDouble(8))

                if (latestCursor.isNull(9)) summary.putNull("created_at")
                else summary.putString("created_at", latestCursor.getString(9))
            }
            latestCursor.close()

            val todayCursor = db.rawQuery(
                """
                SELECT trip_day_number, is_paused, total_walked_km_in_journey
                FROM journey_daily_log
                WHERE journeyId = ? AND date = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(journeyId, queryDate)
            )

            var todayTotalKm: Double? = null
            if (todayCursor.moveToFirst()) {
                summary.putBoolean("today_logged", true)

                if (!todayCursor.isNull(0)) {
                    summary.putInt("today_trip_day_number", todayCursor.getInt(0))
                }

                summary.putBoolean("today_is_paused", todayCursor.getInt(1) == 1)

                if (!todayCursor.isNull(2)) {
                    todayTotalKm = todayCursor.getDouble(2)
                }
            }
            todayCursor.close()

            val previousCursor = db.rawQuery(
                """
                SELECT total_walked_km_in_journey
                FROM journey_daily_log
                WHERE journeyId = ?
                  AND date < ?
                  AND total_walked_km_in_journey IS NOT NULL
                ORDER BY date DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(journeyId, queryDate)
            )

            val previousTotalKm = if (previousCursor.moveToFirst() && !previousCursor.isNull(0)) {
                previousCursor.getDouble(0)
            } else {
                0.0
            }
            previousCursor.close()

            val todayWalkedKm = if (todayTotalKm != null) {
                maxOf(todayTotalKm - previousTotalKm, 0.0)
            } else {
                0.0
            }
            summary.putDouble("today_walked_km", todayWalkedKm)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return summary
    }

    fun getWeeklyStatsJourney(
        journeyId: String,
        offset: Int,
        dailyGoal: Int,
        referenceDate: String? = null,
    ): WritableMap {
        val today = resolveReferenceDate(referenceDate)
        val shift = computeWeekStartShift(today)
        val start = today.minusDays(shift.toLong()).plusWeeks(offset.toLong())
        val end = start.plusDays(6)

        var totalSteps = 0
        var mostSteps = 0
        var leastSteps = Int.MAX_VALUE
        var mostActiveDay = ""
        var leastActiveDay = ""
        var activeMinutes = 0
        var goalDays = 0
        var minSteps = Int.MAX_VALUE
        var maxSteps = 0

        val daysArray = Arguments.createArray()
        val lastValidIndex = when {
            today.isBefore(start) -> -1
            today.isAfter(end) -> 6
            else -> java.time.temporal.ChronoUnit.DAYS.between(start, today).toInt()
        }

        for (i in 0..6) {
            val date = start.plusDays(i.toLong())
            val isFutureDay = i > lastValidIndex
            val steps = if (isFutureDay) {
                0
            } else {
                getCountedStepsForDate(journeyId, date.toString())
            }

            if (!isFutureDay) {
                totalSteps += steps

                if (steps > 0) {
                    activeMinutes += 60
                }

                if (steps > mostSteps) {
                    mostSteps = steps
                    mostActiveDay = dayFull(date)
                }

                if (steps < leastSteps) {
                    leastSteps = steps
                    leastActiveDay = dayFull(date)
                }

                if (steps > maxSteps) maxSteps = steps
                if (steps < minSteps) minSteps = steps
                if (steps >= dailyGoal) goalDays++
            }

            daysArray.pushMap(
                Arguments.createMap().apply {
                    putString("day", dayShort(date))
                    putString("date", date.dayOfMonth.toString())
                    putInt("steps", steps)
                    putBoolean("isToday", date == today)
                    putDouble("distance", calculateDistanceKm(steps))
                    putDouble("calories", calculateCaloriesKcal(steps))
                    putBoolean("goalCompleted", steps >= dailyGoal)
                    putBoolean("future", isFutureDay)
                }
            )
        }

        val prevStart = start.minusWeeks(1)
        val prevEnd = prevStart.plusDays(6)
        val prevSteps = getCountedStepsForRange(journeyId, prevStart, prevEnd)
        val diff = totalSteps - prevSteps
        val improvement = if (prevSteps > 0) diff.toDouble() / prevSteps * 100.0 else 0.0
        val safeMinSteps = if (minSteps == Int.MAX_VALUE) 0 else minSteps

        return Arguments.createMap().apply {
            putString("journeyId", journeyId)
            putInt("goal", dailyGoal * 7)
            putInt("totalSteps", totalSteps)
            putDouble("totalDistance", calculateDistanceKm(totalSteps))
            putDouble("totalCalories", calculateCaloriesKcal(totalSteps))
            putString("mostActiveDay", mostActiveDay)
            putString("leastActiveDay", leastActiveDay)
            putInt("daysGoalCompleted", goalDays)
            putInt("activeMinutes", activeMinutes)
            putArray("days", daysArray)
            putDouble("improvement", improvement)
            putInt("stepDifference", diff)
            putString("startDate", start.toString())
            putString("endDate", end.toString())
            putInt("minSteps", safeMinSteps)
            putInt("maxSteps", maxSteps)
            putString("stepsRange", "$safeMinSteps - $maxSteps")
        }
    }

    fun getMonthlyStatsJourney(
        journeyId: String,
        offset: Int,
        dailyGoal: Int,
        referenceDate: String? = null,
    ): WritableMap {
        val today = resolveReferenceDate(referenceDate)
        val target = today.plusMonths(offset.toLong())
        val monthStart = target.withDayOfMonth(1)
        val monthEnd = target.withDayOfMonth(target.lengthOfMonth())

        val statsByDate = mutableMapOf<String, Pair<Boolean, Int>>()

        db.rawQuery(
            """
            SELECT jdl.date,
                   COALESCE(jdl.counted_steps, 0)
            FROM journey_daily_log jdl
            WHERE jdl.journeyId = ?
              AND jdl.date BETWEEN ? AND ?
            """.trimIndent(),
            arrayOf(journeyId, monthStart.toString(), monthEnd.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val date = cursor.getString(0)
                val storedSteps = cursor.getInt(1)
                val steps = if (storedSteps > 0) storedSteps else getCountedStepsForDate(journeyId, date)
                statsByDate[date] = Pair(steps > 0, steps)
            }
        }

        val daysArray = Arguments.createArray()

        var totalSteps = 0
        var activeMinutes = 0
        var activeDays = 0
        var daysGoalCompleted = 0
        var maxSteps = 0
        var minSteps = Int.MAX_VALUE

        for (dayOfMonth in 1..target.lengthOfMonth()) {
            val date = target.withDayOfMonth(dayOfMonth)
            val dateKey = date.toString()
            val isFutureDay = date.isAfter(today)
            val dayEntry = statsByDate[dateKey]
            val isJourneyActiveDay = !isFutureDay && (dayEntry?.first == true)
            val steps = if (isJourneyActiveDay) dayEntry.second else 0

            if (isJourneyActiveDay) {
                totalSteps += steps

                if (steps > 0) {
                    activeMinutes += 60
                    activeDays += 1
                }

                if (steps >= dailyGoal) {
                    daysGoalCompleted += 1
                }

                if (steps > maxSteps) {
                    maxSteps = steps
                }

                if (steps < minSteps) {
                    minSteps = steps
                }
            }

            daysArray.pushMap(
                Arguments.createMap().apply {
                    putString("day", dayOfMonth.toString())
                    putString("date", dateKey)
                    putInt("steps", steps)
                    putBoolean("isToday", date == today)
                    putDouble("distance", calculateDistanceKm(steps))
                    putDouble("calories", calculateCaloriesKcal(steps))
                    putBoolean("goalCompleted", isJourneyActiveDay && steps >= dailyGoal)
                    putBoolean("future", isFutureDay)
                    putBoolean("journeyActive", isJourneyActiveDay)
                }
            )
        }

        val previousMonthStart = monthStart.minusMonths(1)
        val previousMonthEnd = previousMonthStart.withDayOfMonth(previousMonthStart.lengthOfMonth())

        val previousTotalSteps = db.rawQuery(
            """
                        SELECT COALESCE(SUM(COALESCE(jdl.counted_steps, 0)), 0)
                        FROM journey_daily_log jdl
            WHERE jdl.journeyId = ?
              AND jdl.date BETWEEN ? AND ?
            """.trimIndent(),
            arrayOf(journeyId, previousMonthStart.toString(), previousMonthEnd.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

        val stepDifference = totalSteps - previousTotalSteps
        val improvement = if (previousTotalSteps > 0) {
            stepDifference.toDouble() / previousTotalSteps * 100.0
        } else {
            0.0
        }
        val safeMinSteps = if (minSteps == Int.MAX_VALUE) 0 else minSteps

        return Arguments.createMap().apply {
            putString("journeyId", journeyId)
            putString("month", monthStart.month.toString())
            putString("startDate", monthStart.toString())
            putString("endDate", monthEnd.toString())
            putInt("goal", activeDays * dailyGoal)
            putInt("totalSteps", totalSteps)
            putDouble("totalDistance", calculateDistanceKm(totalSteps))
            putDouble("totalCalories", calculateCaloriesKcal(totalSteps))
            putInt("activeMinutes", activeMinutes)
            putInt("activeDays", activeDays)
            putInt("daysGoalCompleted", daysGoalCompleted)
            putArray("days", daysArray)
            putDouble("improvement", improvement)
            putInt("stepDifference", stepDifference)
            putInt("minSteps", safeMinSteps)
            putInt("maxSteps", maxSteps)
            putString("stepsRange", "$safeMinSteps - $maxSteps")
        }
    }

    fun getPerformanceStatsJourney(
        journeyId: String,
        date: String? = null,
        dailyGoal: Int,
    ): WritableMap {
        val queryDate = date ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val localDate = LocalDate.parse(queryDate)
        val previousDate = localDate.minusDays(1).toString()

        val journeyDayInfo = db.rawQuery(
            """
            SELECT is_paused, COALESCE(counted_steps, 0)
            FROM journey_daily_log
            WHERE journeyId = ? AND date = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(journeyId, queryDate)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                Triple(true, cursor.getInt(0) == 1, cursor.getInt(1))
            } else {
                Triple(false, false, 0)
            }
        }

        val previousJourneyDayInfo = db.rawQuery(
            """
            SELECT is_paused, COALESCE(counted_steps, 0)
            FROM journey_daily_log
            WHERE journeyId = ? AND date = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(journeyId, previousDate)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                Triple(true, cursor.getInt(0) == 1, cursor.getInt(1))
            } else {
                Triple(false, false, 0)
            }
        }

        val isJourneyActiveToday = journeyDayInfo.first && !journeyDayInfo.second
        val todaySteps = if (journeyDayInfo.third > 0) {
            journeyDayInfo.third
        } else {
            getCountedStepsForDate(journeyId, queryDate)
        }
        val yesterdaySteps = if (previousJourneyDayInfo.third > 0) {
            previousJourneyDayInfo.third
        } else {
            getCountedStepsForDate(journeyId, previousDate)
        }

        val stepsByHour = mutableMapOf<Int, Int>()
        db.rawQuery(
            "SELECT hour, COALESCE(steps, 0) FROM journey_hourly_history WHERE journeyId = ? AND date = ?",
            arrayOf(journeyId, queryDate)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                stepsByHour[cursor.getInt(0)] = cursor.getInt(1)
            }
        }

        val activeHours = stepsByHour
            .filterValues { it > 0 }
            .keys
            .sorted()
        val activeMinutes = activeHours.size * 60
        val peakHourEntry = stepsByHour.maxByOrNull { it.value }
        val peakHour = peakHourEntry?.key
        val peakHourLabel = if (peakHour != null) {
            String.format(Locale.getDefault(), "%02d:00", peakHour)
        } else {
            "--"
        }
        val peakHourSteps = peakHourEntry?.value ?: 0

        var longestStreakHours = 0
        var currentStreakHours = 0
        var previousHour: Int? = null

        for (hour in activeHours) {
            currentStreakHours = if (previousHour != null && hour == previousHour + 1) {
                currentStreakHours + 1
            } else {
                1
            }
            longestStreakHours = maxOf(longestStreakHours, currentStreakHours)
            previousHour = hour
        }

        val firstActiveHour = activeHours.firstOrNull()
        val lastActiveHour = activeHours.lastOrNull()
        val sessionWindowMinutes = if (firstActiveHour != null && lastActiveHour != null) {
            ((lastActiveHour - firstActiveHour) + 1) * 60
        } else {
            0
        }
        val restMinutes = maxOf(sessionWindowMinutes - activeMinutes, 0)

        val summary = getJourneyStatsSummary(journeyId, queryDate)
        val todayWalkedKm = if (summary.hasKey("today_walked_km") && !summary.isNull("today_walked_km")) {
            summary.getDouble("today_walked_km")
        } else {
            calculateDistanceKm(todaySteps)
        }
        val estimatedActiveMinutes = if (todaySteps > 0) {
            ceil(todaySteps / 98.0).toInt()
        } else {
            0
        }

        val stepsPerMinute = if (estimatedActiveMinutes > 0) {
            Math.round(todaySteps.toDouble() / estimatedActiveMinutes).toInt()
        } else {
            0
        }
        val averageSpeedKmh = if (estimatedActiveMinutes > 0) {
            todayWalkedKm / (estimatedActiveMinutes / 60.0)
        } else {
            0.0
        }
        val goalProgressPercent = if (dailyGoal > 0) {
            minOf((todaySteps.toDouble() / dailyGoal) * 100.0, 100.0)
        } else {
            0.0
        }
        val remainingSteps = maxOf(dailyGoal - todaySteps, 0)
        val stepDifference = todaySteps - yesterdaySteps
        val improvementPercent = if (yesterdaySteps > 0) {
            ((todaySteps - yesterdaySteps).toDouble() / yesterdaySteps) * 100.0
        } else {
            0.0
        }
        val etaReferenceStepsPerMinute = maxOf(stepsPerMinute.toDouble(), 98.0)
        val etaToGoalMinutes = if (remainingSteps > 0) {
            Math.ceil(remainingSteps.toDouble() / etaReferenceStepsPerMinute).toInt()
        } else {
            0
        }

        return Arguments.createMap().apply {
            putString("journeyId", journeyId)
            putString("date", queryDate)
            putBoolean("journeyActiveToday", isJourneyActiveToday)
            putInt("stepsToday", todaySteps)
            putInt("stepsYesterday", yesterdaySteps)
            putInt("stepDifference", stepDifference)
            putInt("dailyGoal", dailyGoal)
            putDouble("goalProgressPercent", goalProgressPercent)
            putInt("remainingSteps", remainingSteps)
            putInt("activeMinutes", estimatedActiveMinutes)
            putInt("activeHoursCount", activeHours.size)
            putString("peakHour", peakHourLabel)
            putInt("peakHourSteps", peakHourSteps)
            putInt("restMinutes", restMinutes)
            putInt("stepsPerMinute", stepsPerMinute)
            putInt("activeStreakMinutes", longestStreakHours * 60)
            putDouble("averageSpeedKmh", averageSpeedKmh)
            putDouble("todayWalkedKm", todayWalkedKm)
            putDouble("calories", calculateCaloriesKcal(todaySteps))
            putDouble("improvementPercent", improvementPercent)
            putInt("etaToGoalMinutes", etaToGoalMinutes)
            putDouble("strideLengthMeters", 0.8)
        }
    }

    fun ensureJourneyDailyLog(journeyId: String, date: String? = null): WritableMap? {
        val queryDate = date ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        val existingLog = getJourneyDailyLog(journeyId, queryDate)
        if (existingLog != null) {
            return existingLog
        }

        return try {
            val journey = getJourney(journeyId) ?: return null
            val summary = getJourneyStatsSummary(journeyId, queryDate)

            val tripDayNumber = if (summary.hasKey("trip_day_number") && !summary.isNull("trip_day_number")) {
                summary.getInt("trip_day_number") + 1
            } else {
                1
            }

            val currentCheckpoint = if (summary.hasKey("current_checkpoint") && !summary.isNull("current_checkpoint")) {
                summary.getInt("current_checkpoint")
            } else {
                0
            }
            val currentLocationName = if (summary.hasKey("current_location_name") && !summary.isNull("current_location_name")) {
                summary.getString("current_location_name") ?: ""
            } else {
                ""
            }
            val currentLocationLat = if (summary.hasKey("current_location_lat") && !summary.isNull("current_location_lat")) {
                summary.getDouble("current_location_lat")
            } else {
                0.0
            }
            val currentLocationLon = if (summary.hasKey("current_location_lon") && !summary.isNull("current_location_lon")) {
                summary.getDouble("current_location_lon")
            } else {
                0.0
            }
            val totalWalkedKm = summary.getDouble("total_walked_km")
            val progressPercent = summary.getDouble("progress_percent")
            val isPaused = (journey.getString("status") ?: "active") == "paused"

            val success = saveJourneyDailyLog(
                journeyId,
                queryDate,
                tripDayNumber,
                isPaused,
                currentCheckpoint,
                currentLocationName,
                currentLocationLat,
                currentLocationLon,
                totalWalkedKm,
                progressPercent
            )

            if (success) getJourneyDailyLog(journeyId, queryDate) else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun incrementJourneyProgressByDelta(
        journeyId: String,
        deltaSteps: Int,
        date: String? = null,
        hour: Int? = null,
    ): WritableMap? {
        val queryDate = date ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        val baseLog = ensureJourneyDailyLog(journeyId, queryDate) ?: return null
        if (baseLog.hasKey("is_paused") && baseLog.getBoolean("is_paused")) {
            return baseLog
        }
        if (deltaSteps <= 0) {
            return baseLog
        }

        return try {
            val journey = getJourney(journeyId) ?: return null
            val journeyStatus = (journey.getString("status") ?: "active").lowercase(Locale.getDefault())
            val currentTotalWalkedKm = if (
                baseLog.hasKey("total_walked_km_in_journey") &&
                !baseLog.isNull("total_walked_km_in_journey")
            ) {
                baseLog.getDouble("total_walked_km_in_journey")
            } else {
                0.0
            }
            val totalDistanceKm = if (journey.hasKey("total_distance_km") && !journey.isNull("total_distance_km")) {
                journey.getDouble("total_distance_km")
            } else {
                0.0
            }

            val tripDayNumber = if (baseLog.hasKey("trip_day_number")) baseLog.getInt("trip_day_number") else 1
            val isPaused = if (baseLog.hasKey("is_paused")) baseLog.getBoolean("is_paused") else false
            val currentCheckpoint = if (baseLog.hasKey("current_checkpoint")) baseLog.getInt("current_checkpoint") else 0
            val currentLocationName = if (baseLog.hasKey("current_location_name") && !baseLog.isNull("current_location_name")) {
                baseLog.getString("current_location_name") ?: ""
            } else {
                ""
            }
            val currentLocationLat = if (baseLog.hasKey("current_location_lat") && !baseLog.isNull("current_location_lat")) {
                baseLog.getDouble("current_location_lat")
            } else {
                0.0
            }
            val currentLocationLon = if (baseLog.hasKey("current_location_lon") && !baseLog.isNull("current_location_lon")) {
                baseLog.getDouble("current_location_lon")
            } else {
                0.0
            }

            val remainingKm = if (totalDistanceKm > 0) totalDistanceKm - currentTotalWalkedKm else null
            if (remainingKm != null && remainingKm <= 0.0) {
                val success = saveJourneyDailyLog(
                    journeyId,
                    queryDate,
                    tripDayNumber,
                    isPaused,
                    currentCheckpoint,
                    currentLocationName,
                    currentLocationLat,
                    currentLocationLon,
                    totalDistanceKm,
                    100.0
                )

                if (success && journeyStatus != "completed") {
                    markJourneyCompleted(journeyId)
                }

                return if (success) getJourneyDailyLog(journeyId, queryDate) else null
            }

            if (journeyStatus == "completed") {
                return baseLog
            }

            val deltaKm = deltaSteps * 0.0008
            var appliedDeltaKm = deltaKm
            var completedNow = false

            if (remainingKm != null && deltaKm >= remainingKm) {
                appliedDeltaKm = maxOf(remainingKm, 0.0)
                completedNow = appliedDeltaKm > 0.0
            }

            val nextTotalWalkedKm = currentTotalWalkedKm + appliedDeltaKm
            val progressPercent = if (totalDistanceKm > 0) {
                minOf((nextTotalWalkedKm / totalDistanceKm) * 100.0, 100.0)
            } else {
                0.0
            }

            val success = saveJourneyDailyLog(
                journeyId,
                queryDate,
                tripDayNumber,
                isPaused,
                currentCheckpoint,
                currentLocationName,
                currentLocationLat,
                currentLocationLon,
                nextTotalWalkedKm,
                progressPercent
            )

            if (success) {
                val effectiveDeltaSteps = if (appliedDeltaKm < deltaKm) {
                    val cappedSteps = Math.round(appliedDeltaKm / 0.0008).toInt()
                    minOf(deltaSteps, maxOf(cappedSteps, 0))
                } else {
                    deltaSteps
                }

                if (effectiveDeltaSteps > 0) {
                    incrementJourneyCountedSteps(journeyId, queryDate, hour, effectiveDeltaSteps)
                }

                if (completedNow && journeyStatus != "completed") {
                    markJourneyCompleted(journeyId)
                }
            }

            if (success) getJourneyDailyLog(journeyId, queryDate) else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun incrementActiveJourneyProgress(
        deltaSteps: Int,
        date: String? = null,
        hour: Int? = null,
    ): WritableMap? {
        if (deltaSteps <= 0) {
            return null
        }

        val activeJourney = getActiveJourney() ?: return null
        val journeyId = activeJourney.getString("journeyId") ?: return null
        return incrementJourneyProgressByDelta(journeyId, deltaSteps, date, hour)
    }

    // ========================================================================
    // SAVE/UPDATE JOURNEY DAILY LOG
    // ========================================================================

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
        progressPercent: Double
    ): Boolean {
        return try {
            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date())
            val logId = "${journeyId}_${date}"
            
            // Try to update first
            val cursor = db.rawQuery(
                "SELECT id FROM journey_daily_log WHERE journeyId = ? AND date = ?",
                arrayOf(journeyId, date)
            )
            
            if (cursor.moveToFirst()) {
                cursor.close()
                db.execSQL(
                    """
                    UPDATE journey_daily_log SET 
                    trip_day_number = ?, is_paused = ?, current_checkpoint = ?,
                    current_location_name = ?, current_location_lat = ?, current_location_lon = ?,
                    total_walked_km_in_journey = ?, progress_percent = ?
                    WHERE journeyId = ? AND date = ?
                    """.trimIndent(),
                    arrayOf(
                        tripDayNumber, if (isPaused) 1 else 0, currentCheckpoint,
                        currentLocationName, currentLocationLat, currentLocationLon,
                        totalWalkedKm, progressPercent, journeyId, date
                    )
                )
            } else {
                cursor.close()
                db.execSQL(
                    """
                    INSERT INTO journey_daily_log(
                        id, journeyId, date, trip_day_number, is_paused, current_checkpoint,
                        current_location_name, current_location_lat, current_location_lon, counted_steps,
                        total_walked_km_in_journey, progress_percent, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf(
                        logId, journeyId, date, tripDayNumber, if (isPaused) 1 else 0,
                        currentCheckpoint, currentLocationName, currentLocationLat, currentLocationLon, 0,
                        totalWalkedKm, progressPercent, now
                    )
                )
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ========================================================================
    // GET ALL JOURNEYS BY STATUS
    // ========================================================================

    fun getAllJourneys(status: String? = null): WritableArray {
        return try {
            val query = if (status != null) {
                "SELECT journeyId, status FROM journeys WHERE LOWER(TRIM(status)) = LOWER(TRIM(?)) ORDER BY created_at DESC"
            } else {
                "SELECT journeyId, status FROM journeys ORDER BY created_at DESC"
            }

            val cursor = if (status != null) {
                db.rawQuery(query, arrayOf(status))
            } else {
                db.rawQuery(query, null)
            }

            val array = Arguments.createArray()

            while (cursor.moveToNext()) {
                val journeyId = cursor.getString(0)
                val journey = getJourney(journeyId)
                if (journey != null) {
                    array.pushMap(journey)
                }
            }

            cursor.close()
            array
        } catch (e: Exception) {
            e.printStackTrace()
            Arguments.createArray()
        }
    }

    // ========================================================================
    // DELETE JOURNEY
    // ========================================================================

    fun deleteJourney(journeyId: String): Boolean {
        return try {
            // Delete logs first
            db.execSQL("DELETE FROM journey_daily_log WHERE journeyId = ?", arrayOf(journeyId))
            // Delete journey
            db.execSQL("DELETE FROM journeys WHERE journeyId = ?", arrayOf(journeyId))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
