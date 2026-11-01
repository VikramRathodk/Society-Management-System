package org.devvikram.societymanagement.maintenance.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class PaymentAllocatorTest {

    private fun money(value: String) = BigDecimal(value).setScale(2)

    // Oldest first.
    private val bills = listOf(OpenBill(1, money("1000")), OpenBill(2, money("1500")), OpenBill(3, money("800")))

    @Test
    fun `payments fill the oldest bills first`() {
        assertEquals(mapOf(1L to money("1000"), 2L to money("200")), PaymentAllocator.allocate(money("1200"), bills))
    }

    @Test
    fun `a named bill is filled first and the rest goes oldest first`() {
        val split = PaymentAllocator.allocate(money("1000"), bills, preferredBillId = 3)

        assertEquals(listOf(3L, 1L), split.keys.toList())
        assertEquals(money("800"), split[3])
        assertEquals(money("200"), split[1])
    }

    @Test
    fun `the whole outstanding can be paid at once`() {
        assertEquals(3, PaymentAllocator.allocate(money("3300"), bills).size)
    }

    @Test
    fun `more than the outstanding, nothing owed, or an unknown bill is refused`() {
        assertThrows<IllegalArgumentException> { PaymentAllocator.allocate(money("3300.01"), bills) }
        assertThrows<IllegalArgumentException> { PaymentAllocator.allocate(money("1"), emptyList()) }
        assertThrows<IllegalArgumentException> { PaymentAllocator.allocate(money("1"), bills, preferredBillId = 99) }
        assertThrows<IllegalArgumentException> { PaymentAllocator.allocate(money("0"), bills) }
    }
}
