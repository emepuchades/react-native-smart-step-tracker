package com.steptracker.data.steps

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableMap

class UserPreferencesManager(private val configRepo: ConfigRepository) {

    fun getUserPreferences(): WritableMap {
        val language = configRepo.get("user_language") ?: "es"
        val weekStart = configRepo.get("week_start") ?: "monday"

        return Arguments.createMap().apply {
            putString("language", language)
            putString("weekStart", weekStart)
        }
    }

    fun setLanguage(lang: String) {
        configRepo.set("user_language", lang)
    }

    fun setWeekStart(day: String) {
        configRepo.set("week_start", day)
    }

    fun getLanguage(): String = configRepo.get("user_language") ?: "es"

    fun getWeekStart(): String = configRepo.get("week_start") ?: "monday"
}
