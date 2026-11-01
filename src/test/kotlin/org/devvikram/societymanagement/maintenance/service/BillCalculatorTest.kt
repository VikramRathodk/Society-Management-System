package org.devvikram.societymanagement.maintenance.service

import org.devvikram.societymanagement.maintenance.model.ChargeCalcType
import org.devvikram.societymanagement.maintenance.model.ChargeHead
import org.devvikram.societymanagement.society.model.Flat
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.society.model.Wing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BillCalculatorTest {

    private val society = Society(name = "Sunrise").apply { id = 1 }
    private val wing = Wing(society = society, name = "A").apply { id = 1 }
    private var nextFlatId = 1L

    private fun flat(number: String, area: String? = null) =
        Flat(wing = wing, flatNumber = number, areaSqft = area?.let(::BigDecimal)).apply { id = nextFlatId++ }

    private fun head(name: String, type: ChargeCalcType, amount: String, active: Boolean = true, order: Int = 0) =
        ChargeHead(society, name, type, BigDecimal(amount), isActive = active, sortOrder = order)

    private fun money(value: String) = BigDecimal(value).setScale(2)

    @Test
    fun `fixed and per-sq-ft heads add up per flat`() {
        val plan = BillCalculator.plan(
            listOf(flat("101", "850"), flat("102", "1200")),
            listOf(head("Maintenance", ChargeCalcType.PER_SQFT, "2.50"), head("Sinking fund", ChargeCalcType.FIXED, "500", order = 1)),
            roundToRupee = true,
        )

        assertEquals(listOf(money("2625"), money("3500")), plan.bills.map { it.total })
        assertEquals(money("6125"), plan.total)
        val line = plan.bills.first().lines.first()
        assertEquals("Maintenance", line.headName)
        assertEquals(money("850"), line.quantity)
        assertEquals(money("2.50"), line.rate)
        assertTrue(plan.problems.isEmpty())
    }

    @Test
    fun `rounding to rupees is half up per line, and paise are kept when it is off`() {
        val flats = listOf(flat("101", "333.33"))
        val heads = listOf(head("Maintenance", ChargeCalcType.PER_SQFT, "1.50"))

        // 333.33 x 1.50 = 499.995
        assertEquals(money("500"), BillCalculator.plan(flats, heads, roundToRupee = true).bills.single().total)
        assertEquals(money("500.00"), BillCalculator.plan(flats, heads, roundToRupee = false).bills.single().total)

        val small = listOf(head("Water", ChargeCalcType.PER_SQFT, "0.25"))
        // 333.33 x 0.25 = 83.3325
        assertEquals(money("83"), BillCalculator.plan(flats, small, roundToRupee = true).bills.single().total)
        assertEquals(money("83.33"), BillCalculator.plan(flats, small, roundToRupee = false).bills.single().total)
    }

    @Test
    fun `inactive heads are skipped and lines follow sort order`() {
        val plan = BillCalculator.plan(
            listOf(flat("101")),
            listOf(
                head("Parking", ChargeCalcType.FIXED, "300", order = 2),
                head("Maintenance", ChargeCalcType.FIXED, "2000", order = 1),
                head("Old levy", ChargeCalcType.FIXED, "999", active = false),
            ),
            roundToRupee = true,
        )

        assertEquals(listOf("Maintenance", "Parking"), plan.bills.single().lines.map { it.headName })
        assertEquals(money("2300"), plan.bills.single().total)
    }

    @Test
    fun `a flat without area is a problem only when a per-sq-ft head is active`() {
        val noArea = flat("103")
        val perSqft = head("Maintenance", ChargeCalcType.PER_SQFT, "2")

        val plan = BillCalculator.plan(listOf(flat("101", "500"), noArea), listOf(perSqft), roundToRupee = true)
        assertEquals(1, plan.bills.size)
        assertEquals(noArea, plan.problems.single().flat)
        assertEquals("No area set; needed for Maintenance", plan.problems.single().message)

        val fixedOnly = BillCalculator.plan(
            listOf(noArea),
            listOf(perSqft.apply { isActive = false }, head("Flat rate", ChargeCalcType.FIXED, "1500")),
            roundToRupee = true,
        )
        assertTrue(fixedOnly.problems.isEmpty())
        assertEquals(money("1500"), fixedOnly.bills.single().total)
    }
}
