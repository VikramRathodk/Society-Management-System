package org.devvikram.societymanagement.maintenance.service

import org.devvikram.societymanagement.maintenance.model.BillingFrequency
import java.time.LocalDate

data class BillingPeriod(val start: LocalDate, val end: LocalDate, val dueDate: LocalDate) {
    // Rejects periods the database would refuse, and ones long enough to be a typo.
    fun validate() {
        require(!end.isBefore(start)) { "Period end must not be before its start" }
        require(!dueDate.isBefore(start)) { "Due date must not be before the period start" }
        require(!end.isAfter(start.plusYears(1))) { "A billing period can be at most one year long" }
    }
}

object BillingPeriods {
    // The period after the latest issued one, or the current month for a society's first cycle.
    fun next(latestEnd: LocalDate?, today: LocalDate, frequency: BillingFrequency, dueDays: Int): BillingPeriod {
        val start = latestEnd?.plusDays(1) ?: today.withDayOfMonth(1)
        return BillingPeriod(
            start = start,
            end = start.plusMonths(frequency.months).minusDays(1),
            dueDate = start.plusDays(dueDays.toLong()),
        )
    }
}
