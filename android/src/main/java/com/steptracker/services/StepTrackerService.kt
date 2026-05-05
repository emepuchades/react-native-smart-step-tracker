package com.steptracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.steptracker.data.steps.*
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class StepTrackerService : Service(), SensorEventListener {

    companion object {
        private const val KCAL_TO_KJ = 4.184
        private const val KM_TO_MILES = 0.621371
        private const val KM_PER_STEP = 0.0008
        private const val KCAL_PER_STEP = 0.04

        const val CHANNEL_ID = "steptracker_channel"
        const val NOTIF_ID = 1
        const val JOURNEY_CHANNEL_ID = "journey_updates"
        const val JOURNEY_NOTIF_ID = 2

        const val ACTION_STEPS_UPDATE = "com.steptracker.STEPS_UPDATE"
        const val EXTRA_STEPS = "steps_today"

        @Volatile
        var allowStepCounter: Boolean? = false

        @Volatile
        var allowStepDetector: Boolean? = true

        private data class DailyNotificationStats(
            val language: String,
            val progressText: String,
            val progressValue: Int,
            val statsText: String,
            val journeyText: String? = null,
        )

        fun updateNotificationExternally(ctx: Context, steps: Int) {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIF_ID, buildBaseNotification(ctx, steps))
        }

        private fun buildBaseNotification(ctx: Context, steps: Int): Notification {
            val stats = loadDailyNotificationStats(ctx, steps)
            val contentView = RemoteViews(ctx.packageName, R.layout.steps_tracking_notification).apply {
                setTextViewText(R.id.steps_notification_progress_text, stats.progressText)
                setProgressBar(R.id.steps_notification_progress_bar, 1000, stats.progressValue, false)
                setTextViewText(R.id.steps_notification_stats_text, stats.statsText)
                val journeyVisible = stats.journeyText != null
                setViewVisibility(R.id.steps_notification_journey_section, if (journeyVisible) 0 else 8)
                if (journeyVisible) {
                    setTextViewText(R.id.steps_notification_journey_text, stats.journeyText)
                }
            }
            val launchIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val contentIntent = launchIntent?.let {
                PendingIntent.getActivity(
                    ctx,
                    NOTIF_ID,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_steps)
                .setContentTitle(dailyNotificationTitle(stats.language))
                .setContentText(stats.progressText)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(contentView)
                .setCustomBigContentView(contentView)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .apply {
                    if (contentIntent != null) {
                        setContentIntent(contentIntent)
                    }
                }
                .build()
        }

        private fun loadDailyNotificationStats(ctx: Context, steps: Int): DailyNotificationStats {
            val dbHelper = StepsDatabaseHelper(ctx)
            return try {
                val db = dbHelper.readableDatabase
                val configRepo = ConfigRepository(db)
                val historyRepo = StepHistoryRepository(db, configRepo)
                val prefsManager = UserPreferencesManager(configRepo)
                val language = prefsManager.getLanguage().lowercase(Locale.ROOT)
                val distancePref = prefsManager.getDistanceUnit().lowercase(Locale.ROOT)
                val energyPref = prefsManager.getEnergyUnit().lowercase(Locale.ROOT)
                val goal = historyRepo.getDailyGoal()
                val isMiles = distancePref == "miles" || distancePref == "mi"
                val distanceKm = steps * KM_PER_STEP
                val distanceValue = if (isMiles) distanceKm * KM_TO_MILES else distanceKm
                val distanceUnit = if (isMiles) "mi" else "km"
                val energyKcal = steps * KCAL_PER_STEP
                val energyValue = if (energyPref == "kj") energyKcal * KCAL_TO_KJ else energyKcal
                val energyUnit = if (energyPref == "kj") "kJ" else "kcal"
                val progress = if (goal > 0) minOf(((steps.toDouble() / goal) * 1000.0).roundToInt(), 1000) else 0
                val statsText = "\u26A1 ${formatWholeNumber(energyValue, language)} $energyUnit" +
                    "  \u00B7  " +
                    "\u2191 ${formatOneDecimal(distanceValue, language)} $distanceUnit"

                var journeyText: String? = null
                try {
                    val jCursor = db.rawQuery(
                        """
                        SELECT
                            j.origin_name,
                            j.destination_name,
                            COALESCE(j.total_distance_km, 0),
                            COALESCE((
                                SELECT l.total_walked_km_in_journey
                                FROM journey_daily_log l
                                WHERE l.journeyId = j.journeyId
                                  AND l.total_walked_km_in_journey IS NOT NULL
                                ORDER BY l.date DESC
                                LIMIT 1
                            ), 0)
                        FROM journeys j
                        WHERE LOWER(TRIM(j.status)) = 'active'
                        ORDER BY j.started_at DESC
                        LIMIT 1
                        """.trimIndent(),
                        null,
                    )
                    if (jCursor.moveToFirst() && jCursor.getDouble(2) > 0) {
                        val jTotalKm = jCursor.getDouble(2)
                        val jWalkedKm = jCursor.getDouble(3)
                        val jRemainingKm = maxOf(jTotalKm - jWalkedKm, 0.0)
                        val jWalkedValue = if (isMiles) jWalkedKm * KM_TO_MILES else jWalkedKm
                        val jTotalValue = if (isMiles) jTotalKm * KM_TO_MILES else jTotalKm
                        val jProgress = if (jTotalKm > 0.0) ((jWalkedKm / jTotalKm) * 100.0).roundToInt() else 0
                        val origin = jCursor.getString(0)?.takeIf { it.isNotBlank() }
                            ?.let(::compactOriginName)
                            ?: if (language.startsWith("es")) "Origen" else "Origin"
                        val dest = jCursor.getString(1)?.takeIf { it.isNotBlank() }
                            ?: if (language.startsWith("es")) "destino" else "destination"
                        val routeText = "$origin \u2192 $dest"
                        journeyText = "$routeText · ${jProgress}% · ${formatOneDecimal(jWalkedValue, language)}/${formatOneDecimal(jTotalValue, language)} $distanceUnit"
                        if (jRemainingKm <= 0.0) {
                            journeyText = if (language.startsWith("es")) "$routeText · completado" else "$routeText · completed"
                        }
                    }
                    jCursor.close()
                } catch (_: Exception) {}

                DailyNotificationStats(
                    language = language,
                    progressText = buildProgressText(steps, goal, language),
                    progressValue = progress,
                    statsText = statsText,
                    journeyText = journeyText,
                )
            } finally {
                dbHelper.close()
            }
        }

        private fun buildProgressText(steps: Int, goal: Int, language: String): String {
            val unit = if (language.startsWith("es")) "pasos" else "steps"
            val locale = if (language.startsWith("es")) Locale("es", "ES") else Locale.US
            val formatted = java.text.NumberFormat.getNumberInstance(locale).format(steps)
            return "\uD83D\uDC63 $formatted $unit"
        }

        private fun dailyNotificationTitle(language: String): String =
            if (language.startsWith("es")) "Contando pasos" else "Counting steps"

        private fun compactOriginName(value: String): String {
            val candidate = value
                .substringBefore(',')
                .substringBefore(" | ")
                .trim()

            return candidate.ifBlank { value.trim() }
        }

        private fun formatWholeNumber(value: Double, language: String): String {
            val locale = if (language.startsWith("es")) Locale("es", "ES") else Locale.US
            val formatter = DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(locale)).apply {
                isGroupingUsed = true
            }
            return formatter.format(value.roundToInt())
        }

        private fun formatOneDecimal(value: Double, language: String): String {
            val locale = if (language.startsWith("es")) Locale("es", "ES") else Locale.US
            val formatter = DecimalFormat("0.0", DecimalFormatSymbols.getInstance(locale)).apply {
                isGroupingUsed = false
            }
            return formatter.format(value)
        }

        private fun formatCompactSteps(value: Int, language: String): String {
            if (value <= 0) {
                return "0"
            }
            if (value < 1000) {
                return value.toString()
            }

            val locale = if (language.startsWith("es")) Locale("es", "ES") else Locale.US
            val formatter = DecimalFormat("0.#", DecimalFormatSymbols.getInstance(locale)).apply {
                isGroupingUsed = false
            }
            return formatter.format(value / 1000.0) + "k"
        }

    }

    // Repos
    private val dbHelper by lazy { StepsDatabaseHelper(this) }
    private val db by lazy { dbHelper.writableDatabase }

    private val configRepo by lazy { ConfigRepository(db) }
    private val historyRepo by lazy { StepHistoryRepository(db, configRepo) }
    private val journeyRepo by lazy { JourneyRepository(db) }
    private val preferencesManager by lazy { UserPreferencesManager(configRepo) }

    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val stepSensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    private val stepDetector by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) }

    private var lastCounter = -1f
    private var todayOffsetCounter: Int = -1
    private var detectorStepsToday: Int = 0
    private var usingStepCounter = false
    private var usingStepDetector = false

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForeground(NOTIF_ID, buildBaseNotification(this, 0))

        lastCounter = configRepo.get("last_counter")?.toFloatOrNull() ?: -1f
        todayOffsetCounter = historyRepo.getTodayOffset(todayStr())

        if (!registerAvailableSensor()) {
            stopSelf()
            return
        }

        updateNotificationFromDb()
    }

    override fun onDestroy() {
        configRepo.set("last_counter", lastCounter.toString())
        sensorManager.unregisterListener(this)
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(ev: SensorEvent?) {
        ev ?: return
        val nowMs = System.currentTimeMillis()

        when (ev.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> if (usingStepCounter) {
                handleStepCounterEvent(ev.values[0], nowMs)
            }
            Sensor.TYPE_STEP_DETECTOR -> if (usingStepDetector) {
                var deltaSteps = 0
                for (value in ev.values) {
                    deltaSteps += value.toInt()
                }
                handleStepDetectorEvent(deltaSteps, nowMs)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun handleStepCounterEvent(counter: Float, nowMs: Long) {
        val today = todayStr(nowMs)

        if (lastCounter < 0f) {
            val recoveredSteps = initOrFixOffset(counter, nowMs)
            persistState(counter, nowMs, today)
            syncActiveJourneyProgress(recoveredSteps, today, hourStr(nowMs).toInt())
            broadcastAndNotify((counter - todayOffsetCounter).toInt())
            lastCounter = counter
            return
        }

        if (counter < lastCounter) {
            val recoveredSteps = initOrFixOffset(counter, nowMs)
            persistState(counter, nowMs, today)
            syncActiveJourneyProgress(recoveredSteps, today, hourStr(nowMs).toInt())
            broadcastAndNotify(0)
            lastCounter = counter
            return
        }

        val prevDate = configRepo.get("prev_date") ?: today
        if (prevDate != today) {
            initOrFixOffset(counter, nowMs)
            configRepo.set("prev_date", today)
        }

        val delta = counter - lastCounter
        if (delta <= 0f || delta > 2000f) {
            lastCounter = counter
            return
        }

        saveHourly(today, hourStr(nowMs), delta)

        val stepsToday = (counter - todayOffsetCounter)
        historyRepo.setStepsForDate(today, stepsToday.toInt())
        syncActiveJourneyProgress(delta.toInt(), today, hourStr(nowMs).toInt())
        configRepo.set("last_counter", counter.toString())

        broadcastAndNotify(stepsToday.toInt())
        lastCounter = counter
    }

    private fun handleStepDetectorEvent(deltaSteps: Int, nowMs: Long) {
        if (deltaSteps <= 0) return

        val today = todayStr(nowMs)
        val prevDate = configRepo.get("prev_date") ?: today
        if (prevDate != today) {
            detectorStepsToday = safeSteps(historyRepo.getStepsForDate(today))
            configRepo.set("prev_date", today)
        }

        detectorStepsToday += deltaSteps
        saveHourly(today, hourStr(nowMs), deltaSteps.toFloat())
        historyRepo.setStepsForDate(today, detectorStepsToday)
        syncActiveJourneyProgress(deltaSteps, today, hourStr(nowMs).toInt())
        configRepo.set("last_counter", detectorStepsToday.toString())
        configRepo.set("last_counter_time", nowMs.toString())

        broadcastAndNotify(detectorStepsToday)
    }

    private fun broadcastAndNotify(steps: Int) {
        val intent = Intent(ACTION_STEPS_UPDATE).putExtra(EXTRA_STEPS, steps)
        sendBroadcast(intent)
        updateNotificationExternally(this, steps)
    }

    private fun saveHourly(date: String, hour: String, delta: Float) {
        historyRepo.insertOrUpdateHourly(date, hour.toInt(), delta.toInt())
    }

    private fun registerAvailableSensor(): Boolean {
        usingStepCounter = false
        usingStepDetector = false

        if (stepSensor != null) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
            usingStepCounter = true
            return true
        } else if (stepDetector != null) {
            sensorManager.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL)
            usingStepDetector = true
            return true
        }
        return false
    }

    private fun safeSteps(value: Int) = if (value < 0) 0 else value

    private fun initOrFixOffset(counter: Float, timeMs: Long): Int {
        val today = todayStr(timeMs)
        val saved = historyRepo.getTodayOffset(today)
        var recoveredSteps = 0

        if (saved < 0) {
            historyRepo.setTodayOffset(today, counter.toInt())
            todayOffsetCounter = counter.toInt()
        } else {
            if (counter > saved) {
                val missed = (counter - saved)
                val prev = historyRepo.getStepsForDate(today)
                historyRepo.setStepsForDate(today, (prev + missed).toInt())
                historyRepo.setTodayOffset(today, counter.toInt())
                recoveredSteps = missed.toInt()
            }
            todayOffsetCounter = historyRepo.getTodayOffset(today)
        }

        return recoveredSteps
    }

    private fun syncActiveJourneyProgress(deltaSteps: Int, date: String, hour: Int) {
        if (deltaSteps <= 0) {
            return
        }

        try {
            val activeJourney = journeyRepo.getActiveJourney() ?: return
            val journeyId = activeJourney.getString("journeyId") ?: return
            val wasCompleted = activeJourney.getString("status")
                ?.trim()
                ?.lowercase(Locale.getDefault()) == "completed"
            val destinationName = activeJourney.getString("destination_name")

            journeyRepo.incrementJourneyProgressByDelta(journeyId, deltaSteps, date, hour)

            if (!wasCompleted) {
                val updatedJourney = journeyRepo.getJourney(journeyId)
                val isCompleted = updatedJourney
                    ?.getString("status")
                    ?.trim()
                    ?.lowercase(Locale.getDefault()) == "completed"

                if (isCompleted) {
                    val resolvedDestination =
                        updatedJourney?.getString("destination_name") ?: destinationName
                    val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val finalJourneyId = updatedJourney?.getString("journeyId") ?: journeyId
                    val totalDistanceKm = updatedJourney?.getDouble("total_distance_km") ?: 0.0
                    val notification = try {
                        buildJourneyCompletedNotification(
                            resolvedDestination,
                            finalJourneyId,
                            totalDistanceKm,
                        )
                    } catch (notificationError: Exception) {
                        notificationError.printStackTrace()
                        buildJourneyCompletedFallbackNotification(resolvedDestination)
                    }

                    mgr.notify(JOURNEY_NOTIF_ID, notification)
                }
            }
        } catch (error: Exception) {
            error.printStackTrace()
        }
    }

    private fun buildJourneyCompletedNotification(
        destinationName: String?,
        journeyId: String,
        totalDistanceKm: Double,
    ): Notification {
        val language = preferencesManager.getLanguage().lowercase(Locale.ROOT)
        val distanceUnit = preferencesManager.getDistanceUnit().lowercase(Locale.ROOT)
        val safeDestination = destinationName?.takeIf { it.isNotBlank() }
            ?: if (language.startsWith("es")) "Tu destino" else "Your destination"
        val totalSteps = getJourneyTotalSteps(journeyId)
        val badgeText = if (language.startsWith("es")) "Has llegado" else "You've arrived"
        val contentView = RemoteViews(packageName, R.layout.journey_completed_notification).apply {
            setTextViewText(R.id.journey_notification_badge, badgeText)
            setTextViewText(R.id.journey_notification_destination, safeDestination)
            setTextViewText(R.id.journey_notification_time, formatCompletionTime())
            setTextViewText(R.id.journey_notification_duration, formatJourneyDuration(totalDistanceKm))
            setTextViewText(
                R.id.journey_notification_distance,
                formatJourneyDistance(totalDistanceKm, distanceUnit, language),
            )
            setTextViewText(R.id.journey_notification_steps, formatCompactSteps(totalSteps, language))
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                JOURNEY_NOTIF_ID,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        return NotificationCompat.Builder(this, JOURNEY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_journey)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(contentView)
            .setCustomBigContentView(contentView)
            .setContentTitle(badgeText)
            .setContentText(safeDestination)
            .setAutoCancel(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .apply {
                if (contentIntent != null) {
                    setContentIntent(contentIntent)
                }
            }
            .build()
    }

    private fun buildJourneyCompletedFallbackNotification(destinationName: String?): Notification {
        val language = preferencesManager.getLanguage().lowercase(Locale.ROOT)
        val title = if (language.startsWith("es")) "Has llegado" else "You've arrived"
        val safeDestination = destinationName?.takeIf { it.isNotBlank() }
            ?: if (language.startsWith("es")) "Tu destino" else "Your destination"

        return NotificationCompat.Builder(this, JOURNEY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_journey)
            .setContentTitle(title)
            .setContentText(safeDestination)
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeDestination))
            .setAutoCancel(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun formatCompletionTime(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun formatJourneyDuration(distanceKm: Double): String {
        if (distanceKm <= 0.0 || distanceKm.isNaN()) {
            return "0m"
        }

        val totalMinutes = maxOf(1, ((distanceKm / 4.25) * 60.0).roundToInt())
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours <= 0 -> "${minutes}m"
            minutes == 0 -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }

    private fun formatJourneyDistance(distanceKm: Double, distanceUnit: String, language: String): String {
        val locale = if (language.startsWith("es")) Locale("es", "ES") else Locale.US
        val useMiles = distanceUnit == "miles" || distanceUnit == "mi"
        val convertedDistance = if (useMiles) distanceKm * KM_TO_MILES else distanceKm
        val unitLabel = if (useMiles) "mi" else "km"
        val formatter = DecimalFormat("0.0", DecimalFormatSymbols.getInstance(locale))
        return "${formatter.format(maxOf(0.0, convertedDistance))} ${unitLabel}"
    }

    private fun formatCompactSteps(steps: Int, language: String): String {
        if (steps <= 0) {
            return "0"
        }
        if (steps < 1000) {
            return steps.toString()
        }

        val locale = if (language.startsWith("es")) Locale("es", "ES") else Locale.US
        val formatter = DecimalFormat("0.#", DecimalFormatSymbols.getInstance(locale))
        return "${formatter.format(steps / 1000.0)}k"
    }

    private fun getJourneyTotalSteps(journeyId: String): Int {
        val cursor = db.rawQuery(
            """
            SELECT COALESCE(SUM(COALESCE(counted_steps, 0)), 0)
            FROM journey_daily_log
            WHERE journeyId = ?
            """.trimIndent(),
            arrayOf(journeyId),
        )

        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun persistState(counter: Float, timeMs: Long, date: String) {
        configRepo.set("prev_date", date)
        configRepo.set("last_counter", counter.toString())
        configRepo.set("last_counter_time", timeMs.toString())
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Conteo de pasos en segundo plano",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
        if (nm.getNotificationChannel(JOURNEY_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    JOURNEY_CHANNEL_ID,
                    "Actualizaciones de journey",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notificaciones cuando un journey se completa"
                    setShowBadge(true)
                }
            )
        }
    }

    private fun updateNotificationFromDb() {
        val steps = historyRepo.getStepsForDate(todayStr())
        updateNotificationExternally(this, steps)
    }

    private fun todayStr(ms: Long = System.currentTimeMillis()) =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))

    private fun hourStr(ms: Long) =
        SimpleDateFormat("HH", Locale.getDefault()).format(Date(ms))
}
