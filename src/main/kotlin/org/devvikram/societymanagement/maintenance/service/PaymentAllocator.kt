package org.devvikram.societymanagement.maintenance.service

import org.devvikram.societymanagement.maintenance.repository.PaymentCredit
import java.math.BigDecimal

data class OpenBill(val billId: Long, val outstanding: BigDecimal)

// Splits a payment across a flat's open bills. Pure.
object PaymentAllocator {
    // openBills must be oldest first. The preferred bill (if any) is filled first, then the rest oldest
    // first. With `allowExcess` whatever is left over stays unallocated (the flat's credit), even when the
    // flat owes nothing; without it, paying more than the flat owes is refused.
    fun allocate(
        amount: BigDecimal,
        openBills: List<OpenBill>,
        preferredBillId: Long? = null,
        allowExcess: Boolean = false,
    ): LinkedHashMap<Long, BigDecimal> {
        require(amount.signum() > 0) { "Amount must be more than zero" }
        val owed = openBills.fold(BigDecimal.ZERO) { sum, bill -> sum + bill.outstanding }
        if (!allowExcess) {
            require(owed.signum() > 0) { "This flat has nothing outstanding" }
            require(amount <= owed) { "Amount ${amount.setScale(2)} is more than the flat's outstanding ${owed.setScale(2)}" }
        }
        val ordered = if (preferredBillId == null) {
            openBills
        } else {
            val preferred = openBills.firstOrNull { it.billId == preferredBillId }
            requireNotNull(preferred) { "Bill $preferredBillId has nothing outstanding for this flat" }
            listOf(preferred) + openBills.filter { it.billId != preferredBillId }
        }
        val result = LinkedHashMap<Long, BigDecimal>()
        var left = amount
        for (bill in ordered) {
            if (left.signum() == 0) break
            if (bill.outstanding.signum() <= 0) continue
            val part = minOf(left, bill.outstanding)
            result[bill.billId] = part
            left -= part
        }
        return result
    }
}


data class CreditUse(val paymentId: Long, val billId: Long, val amount: BigDecimal)

// How a flat's credit is spent. Pure. Credits and bills must both come oldest first: the oldest payment's
// credit goes first, to the oldest bill first.
object CreditAllocator {
    fun payBills(credits: List<PaymentCredit>, bills: List<OpenBill>): List<CreditUse> {
        val uses = mutableListOf<CreditUse>()
        val owed = bills.map { it.billId to it.outstanding }.toMutableList()
        var billIndex = 0
        for (payment in credits) {
            var left = payment.credit
            while (left.signum() > 0 && billIndex < owed.size) {
                val (billId, outstanding) = owed[billIndex]
                if (outstanding.signum() <= 0) { billIndex++; continue }
                val part = minOf(left, outstanding)
                uses += CreditUse(payment.paymentId, billId, part)
                left -= part
                owed[billIndex] = billId to outstanding - part
                if (outstanding - part <= BigDecimal.ZERO) billIndex++
            }
        }
        return uses
    }

    // Which payments' credit a refund of `amount` uses, oldest first. Refused above the available credit.
    fun take(credits: List<PaymentCredit>, amount: BigDecimal): LinkedHashMap<Long, BigDecimal> {
        require(amount.signum() > 0) { "Amount must be more than zero" }
        val available = credits.fold(BigDecimal.ZERO) { sum, it -> sum + it.credit }
        require(amount <= available) {
            "Amount ${amount.setScale(2)} is more than the flat's unused credit ${available.setScale(2)}"
        }
        val result = LinkedHashMap<Long, BigDecimal>()
        var left = amount
        for (payment in credits) {
            if (left.signum() == 0) break
            if (payment.credit.signum() <= 0) continue
            val part = minOf(left, payment.credit)
            result[payment.paymentId] = part
            left -= part
        }
        return result
    }
}
