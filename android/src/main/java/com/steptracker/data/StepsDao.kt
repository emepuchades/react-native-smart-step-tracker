package com.steptracker.data

import androidx.room.*

@Dao
interface StepsDao {
    /* ── diario ── */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertDaily(d: DailySummary): Long

    @Update
    fun updateDaily(d: DailySummary)

    @Transaction
    fun upsertDaily(d: DailySummary) {
        if (insertDaily(d) == -1L) updateDaily(d)
    }

    /* ── horario ── */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertHourly(list: List<HourlyBreakdown>)

    /* ── lecturas ── */
    @Query("SELECT * FROM daily_summary WHERE date = :d")
    suspend fun summaryFor(d: String): DailySummary?

    @Query("SELECT * FROM daily_summary WHERE date LIKE :month || '-%'")
    suspend fun summariesOfMonth(month: String): List<DailySummary>
}