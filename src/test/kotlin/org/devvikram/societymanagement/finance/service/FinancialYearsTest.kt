package org.devvikram.societymanagement.finance.service

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FinancialYearsTest {
    @Test
    fun `an April year runs April to March and is labelled with both years`() {
        assertEquals("2026-27", FinancialYears.labelOf(LocalDate.of(2026, 4, 1), 4))
        assertEquals("2026-27", FinancialYears.labelOf(LocalDate.of(2027, 3, 31), 4))
        assertEquals("2025-26", FinancialYears.labelOf(LocalDate.of(2026, 3, 31), 4))
        assertEquals(LocalDate.of(2026, 4, 1), FinancialYears.startOf(LocalDate.of(2026, 11, 9), 4))
        assertEquals(LocalDate.of(2027, 3, 31), FinancialYears.endOf(LocalDate.of(2026, 11, 9), 4))
    }

    @Test
    fun `a calendar year is labelled with one year`() {
        assertEquals("2026", FinancialYears.labelOf(LocalDate.of(2026, 12, 31), 1))
        assertEquals(LocalDate.of(2026, 1, 1), FinancialYears.startOf(LocalDate.of(2026, 6, 15), 1))
    }

    @Test
    fun `the century turns cleanly`() {
        assertEquals("2099-00", FinancialYears.labelOf(LocalDate.of(2099, 7, 1), 7))
    }

    @Test
    fun `month must be 1 to 12`() {
        assertFailsWith<IllegalArgumentException> { FinancialYears.startOf(LocalDate.of(2026, 1, 1), 13) }
    }
}
