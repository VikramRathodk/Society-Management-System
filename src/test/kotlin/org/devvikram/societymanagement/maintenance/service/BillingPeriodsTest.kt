package org.devvikram.societymanagement.maintenance.service

import org.devvikram.societymanagement.maintenance.model.BillingFrequency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate

class BillingPeriodsTest {

    private val today = LocalDate.of(2026, 11, 18)

    @Test
    fun `the first cycle is the current month`() {
        val period = BillingPeriods.next(null, today, BillingFrequency.MONTHLY, dueDays = 10)

        assertEquals(LocalDate.of(2026, 11, 1), period.start)
        assertEquals(LocalDate.of(2026, 11, 30), period.end)
        assertEquals(LocalDate.of(2026, 11, 11), period.dueDate)
    }

    @Test
    fun `the next cycle starts the day after the latest one and is sized by the frequency`() {
        val quarterly = BillingPeriods.next(LocalDate.of(2026, 12, 31), today, BillingFrequency.QUARTERLY, dueDays = 15)
        assertEquals(LocalDate.of(2027, 1, 1), quarterly.start)
        assertEquals(LocalDate.of(2027, 3, 31), quarterly.end)
        assertEquals(LocalDate.of(2027, 1, 16), quarterly.dueDate)

        val yearly = BillingPeriods.next(LocalDate.of(2027, 3, 31), today, BillingFrequency.YEARLY, dueDays = 0)
        assertEquals(LocalDate.of(2027, 4, 1), yearly.start)
        assertEquals(LocalDate.of(2028, 3, 31), yearly.end)
    }

    @Test
    fun `month-end edge cases stay inside the month`() {
        val february = BillingPeriods.next(LocalDate.of(2027, 1, 31), today, BillingFrequency.MONTHLY, dueDays = 15)
        assertEquals(LocalDate.of(2027, 2, 1), february.start)
        assertEquals(LocalDate.of(2027, 2, 28), february.end)
    }

    @Test
    fun `invalid periods are refused`() {
        val start = LocalDate.of(2026, 11, 1)
        assertThrows(IllegalArgumentException::class.java) { BillingPeriod(start, start.minusDays(1), start).validate() }
        assertThrows(IllegalArgumentException::class.java) { BillingPeriod(start, start.plusDays(29), start.minusDays(1)).validate() }
        assertThrows(IllegalArgumentException::class.java) { BillingPeriod(start, start.plusYears(1).plusDays(1), start).validate() }
        BillingPeriod(start, start.plusYears(1).minusDays(1), start.plusDays(15)).validate()
    }
}
