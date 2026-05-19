package com.steptracker.data.steps

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import org.json.JSONArray

class UserPreferencesManager(private val config: ConfigRepository) {

    private val KEY_LANGUAGE = "language"
    private val KEY_WEEK_START = "week_start"
    private val KEY_DISTANCE_UNIT = "distance_unit"
    private val KEY_ENERGY_UNIT = "energy_unit"
    private val KEY_NOTIFIED_BADGE_KEYS = "notified_badge_keys"
    private val KEY_BADGE_NOTIFICATIONS_PRIMED = "badge_notifications_primed"
    private val KEY_BODY_WEIGHT = "body_weight"
    private val KEY_BODY_HEIGHT = "body_height"
    private val KEY_BODY_AGE = "body_age"
    private val KEY_BACKUP_FREQUENCY = "backup_frequency"
    private val KEY_LAST_BACKUP_DATE = "last_backup_date"
    private val KEY_TUTORIAL_SEEN = "tutorial_seen"
    private val KEY_DRIVE_BACKUP_FREQUENCY = "drive_backup_frequency"

    fun getLanguage(): String =
        config.get(KEY_LANGUAGE) ?: "en"

    fun getWeekStart(): String =
        config.get(KEY_WEEK_START) ?: "monday"

    fun getDistanceUnit(): String =
        config.get(KEY_DISTANCE_UNIT) ?: "km"

    fun getEnergyUnit(): String =
        config.get(KEY_ENERGY_UNIT) ?: "kcal"

    fun getBodyWeight(): Double? =
        config.get(KEY_BODY_WEIGHT)?.toDoubleOrNull()

    fun getBodyHeight(): Double? =
        config.get(KEY_BODY_HEIGHT)?.toDoubleOrNull()

    fun getBodyAge(): Int? =
        config.get(KEY_BODY_AGE)?.toIntOrNull()

    fun getBackupFrequency(): String =
        config.get(KEY_BACKUP_FREQUENCY) ?: "none"

    fun getLastBackupDate(): String? =
        config.get(KEY_LAST_BACKUP_DATE)

    fun getNotifiedBadgeKeys(): Set<String> {
        val rawValue = config.get(KEY_NOTIFIED_BADGE_KEYS) ?: return emptySet()

        return try {
            val jsonArray = JSONArray(rawValue)
            buildSet {
                for (index in 0 until jsonArray.length()) {
                    val badgeKey = jsonArray.optString(index).trim()
                    if (badgeKey.isNotEmpty()) {
                        add(badgeKey)
                    }
                }
            }
        } catch (_: Exception) {
            rawValue
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }
    }

    fun areBadgeNotificationsPrimed(): Boolean =
        config.get(KEY_BADGE_NOTIFICATIONS_PRIMED) == "1"


    fun setLanguage(lang: String) {
        config.set(KEY_LANGUAGE, lang)
    }

    fun setWeekStart(day: String) {
        config.set(KEY_WEEK_START, day)
    }

    fun setDistanceUnit(unit: String) {
        config.set(KEY_DISTANCE_UNIT, unit)
    }

    fun setEnergyUnit(unit: String) {
        config.set(KEY_ENERGY_UNIT, unit)
    }

    fun setBodyMetrics(weight: Double?, height: Double?, age: Int?) {
        if (weight != null) config.set(KEY_BODY_WEIGHT, weight.toString())
        if (height != null) config.set(KEY_BODY_HEIGHT, height.toString())
        if (age != null) config.set(KEY_BODY_AGE, age.toString())
    }

    fun setBackupFrequency(frequency: String) {
        config.set(KEY_BACKUP_FREQUENCY, frequency)
    }

    fun setLastBackupDate(date: String) {
        config.set(KEY_LAST_BACKUP_DATE, date)
    }

    fun setNotifiedBadgeKeys(keys: Set<String>) {
        config.set(KEY_NOTIFIED_BADGE_KEYS, JSONArray(keys.sorted()).toString())
    }

    fun setBadgeNotificationsPrimed(primed: Boolean) {
        config.set(KEY_BADGE_NOTIFICATIONS_PRIMED, if (primed) "1" else "0")
    }

    fun isTutorialSeen(): Boolean =
        config.get(KEY_TUTORIAL_SEEN) == "1"

    fun setTutorialSeen(seen: Boolean) {
        config.set(KEY_TUTORIAL_SEEN, if (seen) "1" else "0")
    }

    fun getDriveBackupFrequency(): String =
        config.get(KEY_DRIVE_BACKUP_FREQUENCY) ?: "off"

    fun setDriveBackupFrequency(frequency: String) {
        config.set(KEY_DRIVE_BACKUP_FREQUENCY, frequency)
    }

    fun isDriveAutoBackupEnabled(): Boolean =
        getDriveBackupFrequency() != "off"


    fun getUserPreferences(): WritableMap {
        return Arguments.createMap().apply {
            putString(KEY_LANGUAGE, getLanguage())
            putString(KEY_WEEK_START, getWeekStart())
            putString(KEY_DISTANCE_UNIT, getDistanceUnit())
            putString(KEY_ENERGY_UNIT, getEnergyUnit())
            val weight = getBodyWeight()
            val height = getBodyHeight()
            val age = getBodyAge()
            if (weight != null) putDouble(KEY_BODY_WEIGHT, weight) else putNull(KEY_BODY_WEIGHT)
            if (height != null) putDouble(KEY_BODY_HEIGHT, height) else putNull(KEY_BODY_HEIGHT)
            if (age != null) putInt(KEY_BODY_AGE, age) else putNull(KEY_BODY_AGE)
            putString(KEY_BACKUP_FREQUENCY, getBackupFrequency())
            val lastBackup = getLastBackupDate()
            if (lastBackup != null) putString(KEY_LAST_BACKUP_DATE, lastBackup) else putNull(KEY_LAST_BACKUP_DATE)
            putBoolean(KEY_TUTORIAL_SEEN, isTutorialSeen())
            putString(KEY_DRIVE_BACKUP_FREQUENCY, getDriveBackupFrequency())
        }
    }
}
