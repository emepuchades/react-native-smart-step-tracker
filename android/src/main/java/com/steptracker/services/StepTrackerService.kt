package com.steptracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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

        const val DAILY_GOAL_CHANNEL_ID = "daily_goal_channel"
        const val DAILY_GOAL_NOTIF_ID = 3

        const val ACTION_STEPS_UPDATE = "com.steptracker.STEPS_UPDATE"
        const val EXTRA_STEPS = "steps_today"
        const val ACTION_RE_REGISTER_SENSOR = "com.steptracker.RE_REGISTER_SENSOR"
        const val ACTION_MIDNIGHT_RESET = "com.steptracker.MIDNIGHT_RESET"

        @Volatile
        var allowStepCounter: Boolean? = false

        @Volatile
        var allowStepDetector: Boolean? = true

        private data class DailyNotificationStats(
            val language: String,
            val stepsText: String,
            val progressValue: Int,
            val energyText: String,
            val distanceText: String,
            val journeyRouteText: String? = null,
            val journeyPercentText: String? = null,
            val journeyDistanceText: String? = null,
        )

        fun updateNotificationExternally(ctx: Context, steps: Int) {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIF_ID, buildBaseNotification(ctx, steps))
        }

        private fun buildBaseNotification(ctx: Context, steps: Int): Notification {
            val stats = loadDailyNotificationStats(ctx, steps)
            val contentView = RemoteViews(ctx.packageName, R.layout.steps_tracking_notification).apply {
                setTextViewText(R.id.notif_steps_text, stats.stepsText)
                setProgressBar(R.id.steps_notification_progress_bar, 1000, stats.progressValue, false)
                setTextViewText(R.id.notif_energy_text, stats.energyText)
                setTextViewText(R.id.notif_distance_text, stats.distanceText)
                val journeyVisible = stats.journeyRouteText != null
                setViewVisibility(R.id.steps_notification_journey_section, if (journeyVisible) 0 else 8)
                if (journeyVisible) {
                    setTextViewText(R.id.notif_journey_route, stats.journeyRouteText)
                    setTextViewText(R.id.notif_journey_percent, stats.journeyPercentText ?: "")
                    setTextViewText(R.id.notif_journey_distance, stats.journeyDistanceText ?: "")
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
                .setColor(ContextCompat.getColor(ctx, R.color.notif_accent_blue))
                .setContentTitle(dailyNotificationTitle(stats.language))
                .setContentText(stats.stepsText)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(contentView)
                .setCustomBigContentView(contentView)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
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
                val energyText = "${formatWholeNumber(energyValue, language)} $energyUnit"
                val distanceText = "${formatOneDecimal(distanceValue, language)} $distanceUnit"

                var journeyRouteText: String? = null
                var journeyPercentText: String? = null
                var journeyDistanceText: String? = null
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
                        val ns = NotificationStrings.forLanguage(language)
                        val origin = jCursor.getString(0)?.takeIf { it.isNotBlank() }
                            ?.let(::compactOriginName)
                            ?: ns.origin
                        val dest = jCursor.getString(1)?.takeIf { it.isNotBlank() }
                            ?: ns.destination
                        journeyRouteText = "$origin \u2192 $dest"
                        if (jRemainingKm <= 0.0) {
                            journeyPercentText = "100%"
                            journeyDistanceText = ns.completed
                        } else {
                            journeyPercentText = "${jProgress}%"
                            journeyDistanceText = "${formatOneDecimal(jWalkedValue, language)} / ${formatOneDecimal(jTotalValue, language)} $distanceUnit"
                        }
                    }
                    jCursor.close()
                } catch (_: Exception) {}

                DailyNotificationStats(
                    language = language,
                    stepsText = buildStepsText(steps, language),
                    progressValue = progress,
                    energyText = energyText,
                    distanceText = distanceText,
                    journeyRouteText = journeyRouteText,
                    journeyPercentText = journeyPercentText,
                    journeyDistanceText = journeyDistanceText,
                )
            } finally {
                dbHelper.close()
            }
        }

        private fun buildStepsText(steps: Int, language: String): String {
            val ns = NotificationStrings.forLanguage(language)
            val unit = ns.steps
            val locale = ns.locale
            val formatted = java.text.NumberFormat.getNumberInstance(locale).format(steps)
            return "$formatted $unit"
        }

        private fun dailyNotificationTitle(language: String): String =
            NotificationStrings.forLanguage(language).countingSteps

        private fun compactOriginName(value: String): String {
            val candidate = value
                .substringBefore(',')
                .substringBefore(" | ")
                .trim()

            return candidate.ifBlank { value.trim() }
        }

        private fun formatWholeNumber(value: Double, language: String): String {
            val locale = NotificationStrings.forLanguage(language).locale
            val formatter = DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(locale)).apply {
                isGroupingUsed = true
            }
            return formatter.format(value.roundToInt())
        }

        private fun formatOneDecimal(value: Double, language: String): String {
            val locale = NotificationStrings.forLanguage(language).locale
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

            val locale = NotificationStrings.forLanguage(language).locale
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
    private var todayBaseSteps: Int = 0
    private var detectorStepsToday: Int = 0
    private var usingStepCounter = false
    private var usingStepDetector = false
    private var dailyGoalNotifiedDate: String = ""

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        // Cargar estado antes de mostrar la notificación
        lastCounter = configRepo.get("last_counter")?.toFloatOrNull() ?: -1f
        todayOffsetCounter = historyRepo.getTodayOffset(todayStr())
        todayBaseSteps = configRepo.get("today_base_steps")?.toIntOrNull() ?: 0
        // Inicializar desde BD para que el detector no empiece desde 0 tras reboot/reinicio
        val stepsFromDb = safeSteps(historyRepo.getStepsForDate(todayStr()))
        detectorStepsToday = stepsFromDb

        // Android 10+ (API 29+): ACTIVITY_RECOGNITION es requerido para el sensor de pasos
        // Y para arrancar un FGS de tipo "health" (obligatorio desde API 34, Android 36 lo
        // hace fatal incluso en el fallback sin tipo si el manifest declara foregroundServiceType).
        // Si no está concedido, debemos llamar startForeground() igualmente antes de stopSelf()
        // para satisfacer la regla de los 5 segundos de Android; de lo contrario el sistema
        // lanza ForegroundServiceDidNotStartInTimeException y mata la app.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("StepTracker", "onCreate: ACTIVITY_RECOGNITION no concedido, servicio detenido")
            // Satisfacer el contrato de Android: llamar startForeground() antes de stopSelf()
            try {
                val placeholderNotif = buildBaseNotification(this, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this, NOTIF_ID, placeholderNotif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                    )
                } else {
                    startForeground(NOTIF_ID, placeholderNotif)
                }
            } catch (_: Exception) {
                // En API 34+ startForeground con FOREGROUND_SERVICE_TYPE_HEALTH puede lanzar
                // SecurityException si el permiso no está concedido. Lo ignoramos: el fix
                // principal está en StepSyncWorker que no debe llamar startForegroundService()
                // sin el permiso. Este bloque es defensa en profundidad.
            }
            stopSelf()
            return
        }

        // Mostrar pasos reales desde el primer momento (no 0)
        val notification = buildBaseNotification(this, stepsFromDb)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            // startForeground puede lanzar SecurityException o ForegroundServiceStartNotAllowedException
            // si la app está en segundo plano o el sistema rechaza el tipo.
            // Detener el servicio limpiamente para evitar crash en el main thread.
            android.util.Log.w("StepTracker", "startForeground falló: ${e.message}")
            stopSelf()
            return
        }

        if (!registerAvailableSensor()) {
            stopSelf()
            return
        }

        scheduleMidnightReset()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RE_REGISTER_SENSOR -> {
                // Llamada explícita desde startTracking(): re-registrar sensores.
                // Necesario cuando el permiso ACTIVITY_RECOGNITION se concede mientras
                // el servicio ya está en marcha.
                sensorManager.unregisterListener(this)
                if (!registerAvailableSensor()) {
                    stopSelf()
                }
            }
            ACTION_MIDNIGHT_RESET -> {
                // Alarma de medianoche: resetear estado interno para el nuevo día.
                // Se resetea detectorStepsToday y todayBaseSteps aquí mismo para que
                // cualquier evento de sensor que llegue justo después de medianoche
                // empiece desde 0, sin esperar al chequeo prevDate != today.
                detectorStepsToday = 0
                todayBaseSteps = 0
                configRepo.set("today_base_steps", "0")
                broadcastAndNotify(0)
                scheduleMidnightReset()
            }
            // null (START_STICKY) o cualquier otra acción: sensores ya registrados en onCreate().
        }
        return START_STICKY
    }

    private fun scheduleMidnightReset() {
        val midnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val serviceIntent = Intent(this, StepTrackerService::class.java).apply {
            action = ACTION_MIDNIGHT_RESET
        }
        // getForegroundService (API 26+) permite arrancar el servicio desde segundo plano.
        // En API < 26 no hay restricción para startService desde alarmas.
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this, 0, serviceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this, 0, serviceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        // setAndAllowWhileIdle: dispara incluso en modo Doze, sin requerir
        // el permiso SCHEDULE_EXACT_ALARM. Puede retrasarse unos minutos, aceptable.
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, midnight.timeInMillis, pi)
    }

    override fun onDestroy() {
        configRepo.set("last_counter", lastCounter.toString())
        sensorManager.unregisterListener(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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
            // Usar el valor real de la BD: initOrFixOffset ya actualizó la BD con los
            // pasos recuperados. stepsToday calculado como todayBaseSteps + 0 sería 0
            // (porque todayOffsetCounter acaba de ser igualado a counter), pero la BD
            // tiene el total correcto incluyendo los pasos acumulados entre reinicios.
            val stepsToday = historyRepo.getStepsForDate(today)
            broadcastAndNotify(stepsToday)
            lastCounter = counter
            return
        }

        // counter < lastCounter: el sensor se ha reiniciado (reboot del móvil)
        if (counter < lastCounter) {
            val recoveredSteps = initOrFixOffset(counter, nowMs)
            persistState(counter, nowMs, today)
            syncActiveJourneyProgress(recoveredSteps, today, hourStr(nowMs).toInt())
            // Mostrar los pasos previos al reinicio que ya estaban guardados
            broadcastAndNotify(todayBaseSteps)
            lastCounter = counter
            return
        }

        val prevDate = configRepo.get("prev_date") ?: today
        val isDayChange = prevDate != today
        if (isDayChange) {
            initOrFixOffset(counter, nowMs)
            configRepo.set("prev_date", today)
        }

        val delta = counter - lastCounter
        if (delta <= 0f || delta > 2000f) {
            lastCounter = counter
            return
        }

        // No guardar el delta de frontera de medianoche en el histórico horario del
        // nuevo día: esos pasos se acumularon antes de medianoche y no pertenecen
        // a la hora 0 del día actual. El total diario arranca en 0 correctamente.
        if (!isDayChange) {
            saveHourly(today, hourStr(nowMs), delta)
        }

        val stepsToday = todayBaseSteps + maxOf((counter - todayOffsetCounter).toInt(), 0)
        val previousStepsCounter = stepsToday - delta.toInt()
        historyRepo.setStepsForDate(today, stepsToday)
        syncActiveJourneyProgress(delta.toInt(), today, hourStr(nowMs).toInt())
        checkAndNotifyDailyGoal(previousStepsCounter, stepsToday, today)
        configRepo.set("last_counter", counter.toString())

        broadcastAndNotify(stepsToday)
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

        val previousStepsDetector = detectorStepsToday
        detectorStepsToday += deltaSteps
        saveHourly(today, hourStr(nowMs), deltaSteps.toFloat())
        historyRepo.setStepsForDate(today, detectorStepsToday)
        syncActiveJourneyProgress(deltaSteps, today, hourStr(nowMs).toInt())
        checkAndNotifyDailyGoal(previousStepsDetector, detectorStepsToday, today)
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

        if (allowStepCounter != false && stepSensor != null) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
            usingStepCounter = true
            return true
        }
        if (allowStepDetector != false && stepDetector != null) {
            sensorManager.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL)
            usingStepDetector = true
            return true
        }
        // Fallback: usar cualquier sensor disponible si ambas flags bloquean
        if (stepSensor != null) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
            usingStepCounter = true
            return true
        }
        if (stepDetector != null) {
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

        when {
            saved < 0 -> {
                // Primera vez para esta fecha: inicializar offset desde cero
                todayBaseSteps = 0
                configRepo.set("today_base_steps", "0")
                historyRepo.setTodayOffset(today, counter.toInt())
                todayOffsetCounter = counter.toInt()
            }
            counter < saved -> {
                // REBOOT: el sensor hardware se reinició por debajo del offset guardado.
                // Guardar los pasos ya acumulados en BD como base para no perderlos.
                todayBaseSteps = maxOf(historyRepo.getStepsForDate(today), 0)
                configRepo.set("today_base_steps", todayBaseSteps.toString())
                historyRepo.setTodayOffset(today, counter.toInt())
                todayOffsetCounter = counter.toInt()
            }
            counter > saved -> {
                // Reinicio del servicio sin reboot: el contador es mayor que el offset guardado.
                // Recuperar los pasos perdidos durante el tiempo en que el servicio no corría.
                val missed = (counter - saved).toInt()
                val prev = maxOf(historyRepo.getStepsForDate(today), 0)
                historyRepo.setStepsForDate(today, prev + missed)
                historyRepo.setTodayOffset(today, counter.toInt())
                todayOffsetCounter = counter.toInt()
                todayBaseSteps = configRepo.get("today_base_steps")?.toIntOrNull() ?: 0
                recoveredSteps = missed
            }
            else -> {
                // counter == saved: restaurar estado sin cambios
                todayOffsetCounter = saved
                todayBaseSteps = configRepo.get("today_base_steps")?.toIntOrNull() ?: 0
            }
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
        val ns = NotificationStrings.forLanguage(language)
        val safeDestination = destinationName?.takeIf { it.isNotBlank() }
            ?: ns.yourDestination
        val totalSteps = getJourneyTotalSteps(journeyId)
        val badgeText = ns.arrived
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
            .setColor(ContextCompat.getColor(this, R.color.notif_accent_blue))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(contentView)
            .setCustomBigContentView(contentView)
            .setContentTitle(badgeText)
            .setContentText(safeDestination)
            .setAutoCancel(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .apply {
                if (contentIntent != null) {
                    setContentIntent(contentIntent)
                }
            }
            .build()
    }

    private fun checkAndNotifyDailyGoal(previousSteps: Int, newSteps: Int, date: String) {
        if (dailyGoalNotifiedDate == date) return
        val goal = historyRepo.getDailyGoal()
        if (goal <= 0) return
        if (previousSteps >= goal) return
        if (newSteps < goal) return

        dailyGoalNotifiedDate = date
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = try {
            buildDailyGoalNotification(newSteps)
        } catch (e: Exception) {
            e.printStackTrace()
            buildDailyGoalFallbackNotification(newSteps)
        }
        mgr.notify(DAILY_GOAL_NOTIF_ID, notification)
    }

    private fun buildDailyGoalNotification(steps: Int): Notification {
        val language = preferencesManager.getLanguage().lowercase(Locale.ROOT)
        val distanceUnit = preferencesManager.getDistanceUnit().lowercase(Locale.ROOT)
        val energyUnit = preferencesManager.getEnergyUnit().lowercase(Locale.ROOT)
        val ns = NotificationStrings.forLanguage(language)
        val badgeText = ns.dailyGoalTitle
        val distanceKm = steps * KM_PER_STEP
        val isMiles = distanceUnit == "miles" || distanceUnit == "mi"
        val distanceValue = if (isMiles) distanceKm * KM_TO_MILES else distanceKm
        val distanceLabel = if (isMiles) "mi" else "km"
        val energyKcal = steps * KCAL_PER_STEP
        val isKj = energyUnit == "kj"
        val energyValue = if (isKj) energyKcal * KCAL_TO_KJ else energyKcal
        val energyLabel = if (isKj) "kJ" else "kcal"
        val locale = ns.locale
        val stepsFormatted = java.text.NumberFormat.getNumberInstance(locale).format(steps)
        val stepsUnit = ns.steps
        val stepsText = "$stepsFormatted $stepsUnit"
        val distanceFormatter = DecimalFormat("0.0", DecimalFormatSymbols.getInstance(locale))
        val contentView = RemoteViews(packageName, R.layout.daily_goal_notification).apply {
            setTextViewText(R.id.daily_goal_badge, badgeText)
            setTextViewText(R.id.daily_goal_steps, stepsText)
            setTextViewText(R.id.daily_goal_time, formatCompletionTime())
            setTextViewText(R.id.daily_goal_distance, "${distanceFormatter.format(distanceValue)} $distanceLabel")
            setTextViewText(R.id.daily_goal_calories, "${formatWholeNumber(energyValue, language)} $energyLabel")
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                DAILY_GOAL_NOTIF_ID,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, DAILY_GOAL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_steps)
            .setColor(ContextCompat.getColor(this, R.color.notif_accent_blue))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(contentView)
            .setCustomBigContentView(contentView)
            .setContentTitle(badgeText)
            .setContentText(stepsText)
            .setAutoCancel(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .apply {
                if (contentIntent != null) {
                    setContentIntent(contentIntent)
                }
            }
            .build()
    }

    private fun buildDailyGoalFallbackNotification(steps: Int): Notification {
        val language = preferencesManager.getLanguage().lowercase(Locale.ROOT)
        val ns = NotificationStrings.forLanguage(language)
        val title = ns.dailyGoalReached
        val locale = ns.locale
        val stepsFormatted = java.text.NumberFormat.getNumberInstance(locale).format(steps)
        val stepsUnit = ns.steps
        val text = "$stepsFormatted $stepsUnit"
        return NotificationCompat.Builder(this, DAILY_GOAL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_steps)
            .setColor(ContextCompat.getColor(this, R.color.notif_accent_blue))
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    private fun buildJourneyCompletedFallbackNotification(destinationName: String?): Notification {
        val language = preferencesManager.getLanguage().lowercase(Locale.ROOT)
        val ns = NotificationStrings.forLanguage(language)
        val title = ns.arrived
        val safeDestination = destinationName?.takeIf { it.isNotBlank() }
            ?: ns.yourDestination

        return NotificationCompat.Builder(this, JOURNEY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_journey)
            .setColor(ContextCompat.getColor(this, R.color.notif_accent_blue))
            .setContentTitle(title)
            .setContentText(safeDestination)
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeDestination))
            .setAutoCancel(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
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
        val locale = NotificationStrings.forLanguage(language).locale
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

        val locale = NotificationStrings.forLanguage(language).locale
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
        if (nm.getNotificationChannel(DAILY_GOAL_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    DAILY_GOAL_CHANNEL_ID,
                    "Objetivo diario",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notificación cuando se alcanza el objetivo diario de pasos"
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
