package org.devvikram.societymanagement.maintenance.service

import org.devvikram.societymanagement.maintenance.model.BillLineType
import org.devvikram.societymanagement.maintenance.model.LateFeeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class LateFeesTest {

    private val due = LocalDate.of(2026, 11, 15)
    private val december = LocalDate.of(2026, 12, 1)

    private fun bill(id: Long = 1, flatId: Long = 10, outstanding: String = "2625", chargedThrough: LocalDate? = null) =
        LateFeeCandidate(id, "202611-000$id", flatId, due, BigDecimal(outstanding).setScale(2), chargedThrough)

    private fun rule(type: LateFeeType, value: String, graceDays: Int = 0, roundToRupee: Boolean = true) =
        LateFeeRule(type, BigDecimal(value), graceDays, roundToRupee)

    private fun money(value: String) = BigDecimal(value).setScale(2)

    @Test
    fun `no rule charges nothing`() {
        assertTrue(LateFees.lines(listOf(bill()), rule(LateFeeType.NONE, "0"), december).isEmpty())
    }

    @Test
    fun `a fixed fee is charged once per overdue bill`() {
        val lines = LateFees.lines(listOf(bill()), rule(LateFeeType.FIXED, "100"), december).getValue(10)

        val line = lines.single()
        assertEquals(LateFees.LINE_NAME, line.headName)
        assertEquals(BillLineType.LATE_FEE, line.calcType)
        assertEquals(money("100"), line.amount)
        assertEquals(1L, line.sourceBillId)
        assertEquals(december, line.chargedThrough)

        val again = LateFees.lines(listOf(bill(chargedThrough = december)), rule(LateFeeType.FIXED, "100"), LocalDate.of(2027, 1, 1))
        assertTrue(again.isEmpty())
    }

    @Test
    fun `grace days decide whether a bill is overdue`() {
        // Due 15 Nov + 16 days grace = 1 Dec: not yet overdue on 1 Dec, overdue on 2 Dec.
        assertTrue(LateFees.lines(listOf(bill()), rule(LateFeeType.FIXED, "100", graceDays = 16), december).isEmpty())
        assertEquals(1, LateFees.lines(listOf(bill()), rule(LateFeeType.FIXED, "100", graceDays = 16), december.plusDays(1)).size)
    }

    @Test
    fun `simple interest runs from the due date, then from where the last charge stopped`() {
        // 2625 × 18% × 16 days / 365 = 20.71 → 21 rupees.
        val first = LateFees.lines(listOf(bill()), rule(LateFeeType.SIMPLE_INTEREST, "18"), december).getValue(10).single()
        assertEquals(money("21"), first.amount)
        assertEquals(money("16"), first.quantity)
        assertEquals("Bill 202611-0001: 18% a year on 2625.00 for 16 days", first.note)

        // Next cycle on 1 Jan: 31 days since 1 Dec. 2625 × 18% × 31 / 365 = 40.13.
        val next = LateFees.lines(
            listOf(bill(chargedThrough = december)),
            rule(LateFeeType.SIMPLE_INTEREST, "18", roundToRupee = false),
            LocalDate.of(2027, 1, 1),
        ).getValue(10).single()
        assertEquals(money("40.13"), next.amount)
    }

    @Test
    fun `lines are grouped by flat and paid bills are skipped`() {
        val lines = LateFees.lines(
            listOf(bill(1, flatId = 10), bill(2, flatId = 10), bill(3, flatId = 11, outstanding = "0")),
            rule(LateFeeType.FIXED, "50"),
            december,
        )

        assertEquals(setOf(10L), lines.keys)
        assertEquals(listOf(1L, 2L), lines.getValue(10).map { it.sourceBillId })
    }
}
