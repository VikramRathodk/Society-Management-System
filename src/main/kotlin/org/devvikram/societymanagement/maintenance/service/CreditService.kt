package org.devvikram.societymanagement.maintenance.service

import org.devvikram.societymanagement.maintenance.model.PaymentAllocation
import org.devvikram.societymanagement.maintenance.repository.MaintenanceBillRepository
import org.devvikram.societymanagement.maintenance.repository.MaintenancePaymentRepository
import org.devvikram.societymanagement.maintenance.repository.PaymentCredit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

// One flat's credit spent on its bills when a cycle was issued.
data class CreditApplied(val flatId: Long, val uses: List<CreditUse>) {
    val total: BigDecimal get() = uses.fold(BigDecimal.ZERO) { sum, it -> sum + it.amount }
}

// A flat's unused credit: payment amounts minus their allocations minus refunds holding them. It is
// always derived from those rows, never stored, so it can't drift. Callers hold the society lock.
@Service
class CreditService(
    private val paymentRepository: MaintenancePaymentRepository,
    private val billRepository: MaintenanceBillRepository,
) {
    fun creditsOfFlat(flatId: Long): List<PaymentCredit> = paymentRepository.creditsOfFlat(flatId)

    fun creditOfFlat(flatId: Long): BigDecimal =
        creditsOfFlat(flatId).fold(BigDecimal.ZERO) { sum, it -> sum + it.credit }.setScale(2)

    fun creditByFlat(societyId: Long): Map<Long, BigDecimal> = paymentRepository.creditByFlat(societyId)

    // Spends every flat's credit on its open bills (oldest due first, the new ones included), as
    // allocations of the original payments made on `appliedOn`. Returns what was spent, per flat.
    @Transactional(propagation = Propagation.MANDATORY)
    fun applyCredit(societyId: Long, appliedOn: LocalDate): List<CreditApplied> {
        val applied = mutableListOf<CreditApplied>()
        for (flatId in paymentRepository.creditByFlat(societyId).keys) {
            val open = billRepository.findOpenByFlatId(flatId)
            if (open.isEmpty()) continue
            val uses = CreditAllocator.payBills(creditsOfFlat(flatId), open.map { OpenBill(it.id!!, it.outstanding) })
            if (uses.isEmpty()) continue
            val bills = open.associateBy { it.id!! }
            val payments = uses.map { it.paymentId }.distinct().associateWith { id ->
                paymentRepository.findById(id) ?: error("Payment $id not found")
            }
            for (use in uses) {
                val bill = bills.getValue(use.billId)
                val payment = payments.getValue(use.paymentId)
                val allocation = PaymentAllocation(payment, bill, use.amount, fromCredit = true, appliedOn = appliedOn)
                paymentRepository.addAllocation(allocation)
                payment.allocations += allocation
                bill.applyPaidAmount(bill.amountPaid + use.amount)
            }
            applied += CreditApplied(flatId, uses)
        }
        return applied
    }

    // Takes back the credit spent on a cycle's bills (before the cycle is cancelled): the bills lose those
    // amounts and the payments get their credit back. Returns what was released.
    @Transactional(propagation = Propagation.MANDATORY)
    fun releaseCredit(cycleId: Long): List<PaymentAllocation> {
        val allocations = paymentRepository.creditAllocationsOfCycle(cycleId)
        for (allocation in allocations) {
            allocation.bill.applyPaidAmount(allocation.bill.amountPaid - allocation.amount)
        }
        paymentRepository.deleteAllocations(allocations)
        return allocations
    }
}
