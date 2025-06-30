package com.steptracker.domain

import com.steptracker.data.*

class StepsRepository(private val dao: StepsDao) {

    suspend fun stepsOfDay(date: String): Int = dao.summaryFor(date)?.steps ?: 0

    suspend fun stepsOfMonth(month: String): MonthAggregate {
        val list = dao.summariesOfMonth(month)
        val total = list.sumOf { it.steps }
        return MonthAggregate(month, total)
    }

    suspend fun monthAggregates(): List<MonthAggregate> {
        val all = dao.summariesOfMonth("%")
        return all.groupBy { it.date.substring(0, 7) }  // yyyy-MM
            .map { (m, lst) -> MonthAggregate(m, lst.sumOf { it.steps }) }
            .sortedBy { it.month }
    }
}