package org.devvikram.societymanagement.maintenance.service

import org.devvikram.societymanagement.maintenance.repository.PaymentCredit
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

class PaymentAllocatorExcessTest {
    private fun money(value: String) = java.math.BigDecimal(value).setScale(2)

    @Test
    fun `with excess allowed the rest stays unallocated, even when nothing is owed`() {
        val bills = listOf(OpenBill(1, money("1000")))
        assertEquals(mapOf(1L to money("1000")), PaymentAllocator.allocate(money("1500"), bills, allowExcess = true))
        assertEquals(emptyMap<Long, BigDecimal>(), PaymentAllocator.allocate(money("500"), emptyList(), allowExcess = true))
    }
}

class CreditAllocatorTest {
    private fun money(value: String) = java.math.BigDecimal(value).setScale(2)

    @Test
    fun `the oldest payment's credit pays the oldest bill first`() {
        val uses = CreditAllocator.payBills(
            listOf(PaymentCredit(10, money("700")), PaymentCredit(11, money("500"))),
            listOf(OpenBill(1, money("400")), OpenBill(2, money("1000"))),
        )
        assertEquals(
            listOf(
                CreditUse(10, 1, money("400")),
                CreditUse(10, 2, money("300")),
                CreditUse(11, 2, money("500")),
            ),
            uses,
        )
    }

    @Test
    fun `credit beyond the bills stays, and no bills means no use`() {
        assertEquals(listOf(CreditUse(10, 1, money("100"))),
            CreditAllocator.payBills(listOf(PaymentCredit(10, money("900"))), listOf(OpenBill(1, money("100")))))
        assertEquals(emptyList<CreditUse>(), CreditAllocator.payBills(listOf(PaymentCredit(10, money("900"))), emptyList()))
    }

    @Test
    fun `a refund takes the oldest credit first and never more than there is`() {
        val credits = listOf(PaymentCredit(10, money("300")), PaymentCredit(11, money("500")))
        assertEquals(mapOf(10L to money("300"), 11L to money("200")), CreditAllocator.take(credits, money("500")))
        assertThrows<IllegalArgumentException> { CreditAllocator.take(credits, money("800.01")) }
    }
}
