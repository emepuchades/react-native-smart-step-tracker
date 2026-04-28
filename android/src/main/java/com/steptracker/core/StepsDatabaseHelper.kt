package com.steptracker.data.steps

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class StepsDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "steps.db"
        const val DATABASE_VERSION = 5
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

        // Journey tables for stats tracking
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
                counted_steps INTEGER DEFAULT 0,
                total_walked_km_in_journey REAL,
                progress_percent REAL,
                created_at TEXT,
                UNIQUE(journeyId, date),
                FOREIGN KEY(journeyId) REFERENCES journeys(journeyId)
            )
            """
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS journey_hourly_history(
                journeyId TEXT NOT NULL,
                date TEXT NOT NULL,
                hour INTEGER NOT NULL,
                steps INTEGER DEFAULT 0,
                PRIMARY KEY (journeyId, date, hour),
                FOREIGN KEY(journeyId) REFERENCES journeys(journeyId)
            )
            """
        )

        insertDefaultConfigValues(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        android.util.Log.d("StepsDatabaseHelper", "onUpgrade called: $oldVersion → $newVersion")
        
        if (oldVersion < 4) {
            // Create journey tables (new in version 4)
            android.util.Log.d("StepsDatabaseHelper", "Creating journey tables...")
            
            try {
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
                android.util.Log.d("StepsDatabaseHelper", "Created 'journeys' table")
            } catch (e: Exception) {
                android.util.Log.e("StepsDatabaseHelper", "Error creating journeys table: ${e.message}", e)
            }
            
            try {
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
                android.util.Log.d("StepsDatabaseHelper", "Created 'journey_daily_log' table")
            } catch (e: Exception) {
                android.util.Log.e("StepsDatabaseHelper", "Error creating journey_daily_log table: ${e.message}", e)
            }
            
            insertDefaultConfigValues(db)
        }

        if (oldVersion < 5) {
            try {
                db.execSQL(
                    "ALTER TABLE journey_daily_log ADD COLUMN counted_steps INTEGER DEFAULT 0"
                )
                android.util.Log.d("StepsDatabaseHelper", "Added counted_steps to journey_daily_log")
            } catch (_: Exception) {
            }

            try {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS journey_hourly_history(
                        journeyId TEXT NOT NULL,
                        date TEXT NOT NULL,
                        hour INTEGER NOT NULL,
                        steps INTEGER DEFAULT 0,
                        PRIMARY KEY (journeyId, date, hour),
                        FOREIGN KEY(journeyId) REFERENCES journeys(journeyId)
                    )
                    """
                )
                android.util.Log.d("StepsDatabaseHelper", "Ensured journey_hourly_history table")
            } catch (e: Exception) {
                android.util.Log.e("StepsDatabaseHelper", "Error creating journey_hourly_history: ${e.message}", e)
            }
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
