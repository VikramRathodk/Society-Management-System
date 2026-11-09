package org.devvikram.societymanagement.finance.service

import java.time.LocalDate

// Financial years that start in a given month (April for most Indian societies). A year starting in
// January is labelled "2026"; any other "2026-27".
object FinancialYears {
    fun startOf(date: LocalDate, startMonth: Int): LocalDate {
        require(startMonth in 1..12) { "Start month must be 1 to 12" }
        val year = if (date.monthValue >= startMonth) date.year else date.year - 1
        return LocalDate.of(year, startMonth, 1)
    }

    fun endOf(date: LocalDate, startMonth: Int): LocalDate = startOf(date, startMonth).plusYears(1).minusDays(1)

    fun labelOf(date: LocalDate, startMonth: Int): String {
        val start = startOf(date, startMonth)
        return if (startMonth == 1) "${start.year}" else "${start.year}-${"%02d".format((start.year + 1) % 100)}"
    }
}
