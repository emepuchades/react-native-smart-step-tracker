package com.steptracker.data.steps

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

class UserPreferencesManager(private val config: ConfigRepository) {

    private val KEY_LANGUAGE = "language"
    private val KEY_WEEK_START = "week_start"
    private val KEY_DISTANCE_UNIT = "distance_unit"
    private val KEY_ENERGY_UNIT = "energy_unit"

    fun getLanguage(): String =
        config.get(KEY_LANGUAGE) ?: "en"

    fun getWeekStart(): String =
        config.get(KEY_WEEK_START) ?: "monday"

    fun getDistanceUnit(): String =
        config.get(KEY_DISTANCE_UNIT) ?: "km"

    fun getEnergyUnit(): String =
        config.get(KEY_ENERGY_UNIT) ?: "kcal"


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


    fun getUserPreferences(): WritableMap {
        return Arguments.createMap().apply {
            putString(KEY_LANGUAGE, getLanguage())
            putString(KEY_WEEK_START, getWeekStart())
            putString(KEY_DISTANCE_UNIT, getDistanceUnit())
            putString(KEY_ENERGY_UNIT, getEnergyUnit())
        }
    }
}
