package com.steptracker.data.steps

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

class ConfigRepository(private val db: SQLiteDatabase) {

    fun get(key: String): String? {
        val cursor = db.rawQuery(
            "SELECT value FROM config WHERE key = ?",
            arrayOf(key)
        )
        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    fun set(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        db.insertWithOnConflict(
            "config", null, values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }
}
