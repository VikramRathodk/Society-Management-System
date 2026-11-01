package org.devvikram.societymanagement.maintenance.service

import java.math.BigDecimal

data class OpenBill(val billId: Long, val outstanding: BigDecimal)

// Splits a payment across a flat's open bills. Pure.
object PaymentAllocator {
    // openBills must be oldest first. The preferred bill (if any) is filled first, then the rest oldest
    // first. Refused when the amount is more than everything the flat owes: advance payments are not
    // supported yet.
    fun allocate(amount: BigDecimal, openBills: List<OpenBill>, preferredBillId: Long? = null): LinkedHashMap<Long, BigDecimal> {
        require(amount.signum() > 0) { "Amount must be more than zero" }
        val owed = openBills.fold(BigDecimal.ZERO) { sum, bill -> sum + bill.outstanding }
        require(owed.signum() > 0) { "This flat has nothing outstanding" }
        require(amount <= owed) { "Amount ${amount.setScale(2)} is more than the flat's outstanding ${owed.setScale(2)}" }
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
