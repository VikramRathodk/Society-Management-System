package org.devvikram.societymanagement.maintenance.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.maintenance.model.BillStatus
import org.devvikram.societymanagement.maintenance.model.MaintenancePayment
import org.devvikram.societymanagement.maintenance.model.PaymentAllocation
import org.devvikram.societymanagement.maintenance.model.PaymentMethod
import org.devvikram.societymanagement.maintenance.model.PaymentStatus
import org.devvikram.societymanagement.maintenance.repository.MaintenanceBillRepository
import org.devvikram.societymanagement.maintenance.repository.MaintenanceCycleRepository
import org.devvikram.societymanagement.maintenance.repository.MaintenancePaymentRepository
import org.devvikram.societymanagement.maintenance.repository.PaymentSearch
import org.devvikram.societymanagement.society.repository.FlatRepository
import org.devvikram.societymanagement.society.repository.SocietyRepository
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.repository.UserRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class RecordPaymentCommand(
    val flatId: Long,
    val amount: BigDecimal,
    val paidOn: LocalDate,
    val method: PaymentMethod,
    val reference: String? = null,
    val note: String? = null,
    // Pay this bill first; the rest (if any) goes to the oldest bills.
    val billId: Long? = null,
    // The finance cash/bank ledger the money went into (checked by finance when the books are kept).
    val depositAccountId: Long? = null,
)

// Offline payments against a flat's bills. *Who* may act is decided by the caller
// (MaintenancePaymentController). Writes take the society lock before reading any bill, so the amounts
// they work from are current.
@Service
class PaymentService(
    private val cycleRepository: MaintenanceCycleRepository,
    private val billRepository: MaintenanceBillRepository,
    private val paymentRepository: MaintenancePaymentRepository,
    private val flatRepository: FlatRepository,
    private val societyRepository: SocietyRepository,
    private val userRepository: UserRepository,
    private val settings: SocietySettingsService,
    private val events: ApplicationEventPublisher,
) {
    // Records the payment with a new receipt number and applies it to the flat's bills, oldest due first
    // (or the named bill first). Returns the payment id.
    @Transactional
    fun record(societyId: Long, command: RecordPaymentCommand, recorderId: Long, today: LocalDate): Long {
        require(command.amount.signum() > 0) { "Amount must be more than zero" }
        require(command.amount.scale() <= 2) { "Amount can have at most 2 decimal places" }
        require(!command.paidOn.isAfter(today)) { "Paid-on date must not be in the future" }
        val flat = flatRepository.findBySocietyId(societyId).firstOrNull { it.id == command.flatId }
        requireNotNull(flat) { "Flat ${command.flatId} is not in this society" }

        cycleRepository.lockSociety(societyId)
        val open = billRepository.findOpenByFlatId(flat.id!!)
        if (command.billId != null) {
            require(open.any { it.id == command.billId }) { "Bill ${command.billId} is not an open bill of this flat" }
        }
        val amount = command.amount.setScale(2)
        val acceptAdvance = settings.boolean(societyId, SocietySettingKeys.ACCEPT_ADVANCE_PAYMENTS)
        // With advance payments on, what the bills don't take stays on the payment as the flat's credit.
        val split = PaymentAllocator.allocate(amount, open.map { OpenBill(it.id!!, it.outstanding) }, command.billId, acceptAdvance)

        val society = societyRepository.findById(societyId) ?: throw NoSuchElementException("Society $societyId not found")
        val payment = MaintenancePayment(
            society = society,
            flat = flat,
            receiptNumber = "R-%06d".format(paymentRepository.countBySocietyId(societyId) + 1),
            amount = amount,
            paidOn = command.paidOn,
            method = command.method,
            reference = command.reference?.trim()?.takeIf { it.isNotEmpty() },
            note = command.note?.trim()?.takeIf { it.isNotEmpty() },
            recordedBy = user(recorderId),
        ).also { it.depositAccountId = command.depositAccountId }
        val billsById = open.associateBy { it.id!! }
        for ((billId, part) in split) {
            val bill = billsById.getValue(billId)
            payment.allocations += PaymentAllocation(payment, bill, part)
            bill.applyPaidAmount(bill.amountPaid + part)
        }
        val saved = paymentRepository.save(payment)
        events.publishEvent(
            MaintenancePaymentRecorded(
                paymentId = saved.id!!,
                societyId = societyId,
                flatId = flat.id!!,
                flatNumber = flat.flatNumber,
                receiptNumber = saved.receiptNumber,
                amount = saved.amount,
            )
        )
        return saved.id!!
    }

    // Takes the payment back off its bills (they become UNPAID / PARTIAL again), including bills its credit
    // paid later. The payment and its allocations stay as history. Refused when a bill it paid was waived
    // since (the write-off would grow), or while a refund holds part of its credit.
    @Transactional
    fun void(id: Long, actorId: Long, reason: String) {
        require(reason.isNotBlank()) { "A reason is required" }
        val societyId = paymentRepository.findSocietyId(id) ?: throw NoSuchElementException("Payment $id not found")
        cycleRepository.lockSociety(societyId)
        val payment = get(id)
        if (payment.status != PaymentStatus.RECORDED) throw ConflictException("Payment is already void")
        // Its credit paid out as a refund can't silently come back: undo the refund first.
        if (paymentRepository.refundedFrom(id).signum() > 0) {
            throw ConflictException("Part of this payment's credit is in a refund; void or reject the refund first")
        }
        payment.allocations.firstOrNull { it.bill.status !in BillStatus.OPEN && it.bill.status != BillStatus.PAID }?.let {
            throw ConflictException("Bill ${it.bill.billNumber} is ${it.bill.status}; a payment on it cannot be voided")
        }
        for (allocation in payment.allocations) {
            allocation.bill.applyPaidAmount(allocation.bill.amountPaid - allocation.amount)
        }
        payment.status = PaymentStatus.VOID
        payment.voidedBy = user(actorId)
        payment.voidedAt = Instant.now()
        payment.voidReason = reason.trim()
        paymentRepository.save(payment)
        events.publishEvent(MaintenancePaymentVoided(id, societyId))
    }

    // What of the payment isn't on any bill or in a refund (only meaningful while it is RECORDED).
    fun creditLeft(payment: MaintenancePayment): BigDecimal {
        if (payment.status != PaymentStatus.RECORDED) return BigDecimal.ZERO.setScale(2)
        val allocated = payment.allocations.fold(BigDecimal.ZERO) { sum, it -> sum + it.amount }
        return (payment.amount - allocated - paymentRepository.refundedFrom(payment.id!!)).setScale(2)
    }

    fun get(id: Long): MaintenancePayment =
        paymentRepository.findById(id) ?: throw NoSuchElementException("Payment $id not found")

    fun search(search: PaymentSearch): List<MaintenancePayment> = paymentRepository.search(search)

    fun forExport(societyId: Long, from: LocalDate, to: LocalDate): List<MaintenancePayment> =
        paymentRepository.findForExport(societyId, from, to)

    // What paid this bill (void payments left out), oldest first.
    fun allocationsOfBill(billId: Long): List<PaymentAllocation> = paymentRepository.activeAllocationsOfBill(billId)

    private fun user(id: Long): User = userRepository.findById(id) ?: throw NoSuchElementException("User $id not found")
}
