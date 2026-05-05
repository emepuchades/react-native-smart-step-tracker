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

    fun getLanguage(): String =
        config.get(KEY_LANGUAGE) ?: "en"

    fun getWeekStart(): String =
        config.get(KEY_WEEK_START) ?: "monday"

    fun getDistanceUnit(): String =
        config.get(KEY_DISTANCE_UNIT) ?: "km"

    fun getEnergyUnit(): String =
        config.get(KEY_ENERGY_UNIT) ?: "kcal"

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

    fun setNotifiedBadgeKeys(keys: Set<String>) {
        config.set(KEY_NOTIFIED_BADGE_KEYS, JSONArray(keys.sorted()).toString())
    }

    fun setBadgeNotificationsPrimed(primed: Boolean) {
        config.set(KEY_BADGE_NOTIFICATIONS_PRIMED, if (primed) "1" else "0")
    }


    fun getUserPreferences(): WritableMap {
        return Arguments.createMap().apply {
            putString(KEY_LANGUAGE, getLanguage())
            putString(KEY_WEEK_START, getWeekStart())
            putString(KEY_DISTANCE_UNIT, getDistanceUnit())
            putString(KEY_ENERGY_UNIT, getEnergyUnit())
        }
    }
}
