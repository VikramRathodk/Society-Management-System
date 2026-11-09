package org.devvikram.societymanagement.maintenance.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.maintenance.model.MaintenanceRefund
import org.devvikram.societymanagement.maintenance.model.PaymentMethod
import org.devvikram.societymanagement.maintenance.model.RefundAllocation
import org.devvikram.societymanagement.maintenance.model.RefundStatus
import org.devvikram.societymanagement.maintenance.repository.MaintenanceCycleRepository
import org.devvikram.societymanagement.maintenance.repository.MaintenanceRefundRepository
import org.devvikram.societymanagement.society.repository.FlatRepository
import org.devvikram.societymanagement.society.repository.SocietyRepository
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.devvikram.societymanagement.user.repository.UserRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class RefundCommand(
    val flatId: Long,
    val amount: BigDecimal,
    val paidOn: LocalDate,
    val method: PaymentMethod,
    val reason: String,
    val reference: String? = null,
    // The finance cash/bank ledger it was paid from (checked by finance when the books are kept).
    val paidFromAccountId: Long? = null,
)

// Finance hears about a refund that counts (completed straight away, or approved).
data class MaintenanceRefundCompleted(val refundId: Long, val societyId: Long)

// Finance hears about a completed refund being voided.
data class MaintenanceRefundVoided(val refundId: Long, val societyId: Long)

// Paying a flat's unused credit back. The refund takes credit from the flat's oldest payments first and
// holds it while waiting, so the same credit can never be refunded twice or spent on a bill meanwhile.
// Above `financeApprovalAbove` it waits for a second person (never its maker), like finance payments.
// Every write takes the society lock (the one payments and billing use).
@Service
class RefundService(
    private val refundRepository: MaintenanceRefundRepository,
    private val cycleRepository: MaintenanceCycleRepository,
    private val creditService: CreditService,
    private val flatRepository: FlatRepository,
    private val societyRepository: SocietyRepository,
    private val userRepository: UserRepository,
    private val settings: SocietySettingsService,
    private val events: ApplicationEventPublisher,
) {
    fun get(id: Long): MaintenanceRefund = refundRepository.findById(id) ?: throw NoSuchElementException("Refund $id not found")

    fun search(societyId: Long, flatIds: Set<Long>?, statuses: Set<RefundStatus>?): List<MaintenanceRefund> =
        refundRepository.search(societyId, flatIds, statuses)

    fun creditOfFlat(societyId: Long, flatId: Long): BigDecimal {
        requireFlat(societyId, flatId)
        return creditService.creditOfFlat(flatId)
    }

    @Transactional
    fun create(societyId: Long, command: RefundCommand, userId: Long, today: LocalDate): Long {
        require(command.amount.signum() > 0) { "Amount must be more than zero" }
        require(command.amount.scale() <= 2) { "Amount can have at most 2 decimal places" }
        require(!command.paidOn.isAfter(today)) { "Paid-on date must not be in the future" }
        val reason = clean(command.reason) ?: throw IllegalArgumentException("Give a reason for the refund")
        val flat = requireFlat(societyId, command.flatId)

        cycleRepository.lockSociety(societyId)
        val amount = command.amount.setScale(2)
        val taken = CreditAllocator.take(creditService.creditsOfFlat(flat.id!!), amount)
        val limit = BigDecimal.valueOf(settings.integer(societyId, SocietySettingKeys.FINANCE_APPROVAL_ABOVE))
        val needsApproval = limit.signum() > 0 && amount > limit

        val society = societyRepository.findById(societyId) ?: throw NoSuchElementException("Society $societyId not found")
        val refund = MaintenanceRefund(
            society = society,
            flat = flat,
            refundNumber = "RF-%06d".format(refundRepository.countBySocietyId(societyId) + 1),
            amount = amount,
            paidOn = command.paidOn,
            method = command.method,
            reason = reason,
            status = if (needsApproval) RefundStatus.PENDING_APPROVAL else RefundStatus.COMPLETED,
            createdBy = userId,
            paidFromAccountId = command.paidFromAccountId,
            reference = clean(command.reference),
        )
        taken.forEach { (paymentId, part) -> refund.allocations += RefundAllocation(refund, paymentId, part) }
        val saved = refundRepository.save(refund)
        if (needsApproval) {
            events.publishEvent(
                MaintenanceRefundNeedsApproval(
                    saved.id!!, societyId, saved.refundNumber, flat.flatNumber, amount, userId,
                    userRepository.findById(userId)?.name ?: "Someone",
                )
            )
        } else {
            events.publishEvent(MaintenanceRefundCompleted(saved.id!!, societyId))
        }
        return saved.id!!
    }

    @Transactional
    fun approve(id: Long, userId: Long) {
        val refund = waiting(id)
        if (refund.createdBy == userId) throw AccessDeniedException("You recorded ${refund.refundNumber}, so someone else has to approve it")
        decide(refund, RefundStatus.COMPLETED, userId, null)
        events.publishEvent(MaintenanceRefundDecided(id, refund.createdBy, refund.refundNumber, approved = true, reason = null))
        events.publishEvent(MaintenanceRefundCompleted(id, refund.society.id!!))
    }

    @Transactional
    fun reject(id: Long, userId: Long, reason: String) {
        val refund = waiting(id)
        if (refund.createdBy == userId) throw AccessDeniedException("You recorded ${refund.refundNumber}; withdraw it instead")
        val why = clean(reason) ?: throw IllegalArgumentException("Give a reason for rejecting")
        decide(refund, RefundStatus.REJECTED, userId, why)
        events.publishEvent(MaintenanceRefundDecided(id, refund.createdBy, refund.refundNumber, approved = false, reason = why))
    }

    // The maker takes a waiting refund back; its credit is free again.
    @Transactional
    fun withdraw(id: Long, userId: Long) {
        val refund = waiting(id)
        if (refund.createdBy != userId) throw AccessDeniedException("Only the person who recorded ${refund.refundNumber} can withdraw it")
        decide(refund, RefundStatus.REJECTED, userId, "Withdrawn by the maker")
    }

    // A completed refund that didn't happen (or was entered wrongly): the credit goes back to the flat.
    @Transactional
    fun void(id: Long, userId: Long, reason: String) {
        val why = clean(reason) ?: throw IllegalArgumentException("A reason is required")
        val societyId = refundRepository.findSocietyId(id) ?: throw NoSuchElementException("Refund $id not found")
        cycleRepository.lockSociety(societyId)
        val refund = get(id)
        if (refund.status != RefundStatus.COMPLETED) throw ConflictException("Only a completed refund can be voided (${refund.refundNumber} is ${refund.status})")
        refund.status = RefundStatus.VOID
        refund.voidedAt = Instant.now()
        refund.voidedBy = userId
        refund.voidReason = why
        refundRepository.save(refund)
        events.publishEvent(MaintenanceRefundVoided(id, societyId))
    }

    private fun waiting(id: Long): MaintenanceRefund {
        val societyId = refundRepository.findSocietyId(id) ?: throw NoSuchElementException("Refund $id not found")
        cycleRepository.lockSociety(societyId)
        val refund = get(id)
        if (refund.status != RefundStatus.PENDING_APPROVAL) throw ConflictException("${refund.refundNumber} is not waiting for approval")
        return refund
    }

    private fun decide(refund: MaintenanceRefund, status: RefundStatus, userId: Long, reason: String?) {
        refund.status = status
        refund.decidedAt = Instant.now()
        refund.decidedBy = userId
        refund.decisionReason = reason
        refundRepository.save(refund)
    }

    private fun requireFlat(societyId: Long, flatId: Long) =
        flatRepository.findBySocietyId(societyId).firstOrNull { it.id == flatId }
            ?: throw IllegalArgumentException("Flat $flatId is not in this society")

    private fun clean(text: String?): String? = text?.trim()?.ifEmpty { null }?.also {
        require(it.length <= 500) { "Text can be at most 500 characters" }
    }
}
