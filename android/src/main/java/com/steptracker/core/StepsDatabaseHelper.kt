package com.steptracker.data.steps

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class StepsDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "steps.db"
        const val DATABASE_VERSION = 4
    }

    override fun onCreate(db: SQLiteDatabase) {

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_history(
                date TEXT PRIMARY KEY,
                steps INTEGER DEFAULT 0,
                offset INTEGER DEFAULT 0,
                goal INTEGER DEFAULT 10000
            )
            """
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hourly_history(
                date TEXT,
                hour INTEGER,
                steps INTEGER,
                PRIMARY KEY (date, hour)
            )
            """
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS config(
                key TEXT PRIMARY KEY,
                value TEXT
            )
            """
        )
        insertDefaultConfigValues(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) {
            insertDefaultConfigValues(db)
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }

    private fun insertDefaultConfigValues(db: SQLiteDatabase) {
        db.execSQL("INSERT OR IGNORE INTO config(key, value) VALUES ('week_start', 'monday')")
        db.execSQL("INSERT OR IGNORE INTO config(key, value) VALUES ('distance_unit', 'km')")
        db.execSQL("INSERT OR IGNORE INTO config(key, value) VALUES ('energy_unit', 'kcal')")
    }
}
