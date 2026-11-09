package org.devvikram.societymanagement.maintenance.dto

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.devvikram.societymanagement.maintenance.model.MaintenanceRefund
import org.devvikram.societymanagement.maintenance.model.PaymentMethod
import org.devvikram.societymanagement.maintenance.model.RefundStatus
import org.devvikram.societymanagement.maintenance.service.RefundCommand
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class RefundRequest(
    @field:NotNull val flatId: Long?,
    @field:NotNull @field:DecimalMin("0.01") @field:Digits(integer = 10, fraction = 2) val amount: BigDecimal?,
    @field:NotNull val paidOn: LocalDate?,
    @field:NotNull val method: PaymentMethod?,
    @field:NotBlank @field:Size(max = 500) val reason: String?,
    @field:Size(max = 100) val reference: String? = null,
    // The finance cash/bank ledger it was paid from.
    val paidFromAccountId: Long? = null,
) {
    fun toCommand() = RefundCommand(flatId!!, amount!!, paidOn!!, method!!, reason!!, reference, paidFromAccountId)
}

data class RefundAllocationResponse(val paymentId: Long, val amount: BigDecimal)

data class RefundResponse(
    val id: Long,
    val refundNumber: String,
    val societyId: Long,
    val flatId: Long,
    val wingName: String,
    val flatNumber: String,
    val amount: BigDecimal,
    val paidOn: LocalDate,
    val method: PaymentMethod,
    val paidFromAccountId: Long?,
    val reference: String?,
    val reason: String,
    val status: RefundStatus,
    val createdAt: Instant?,
    val createdBy: Long,
    val createdByName: String?,
    val decidedAt: Instant?,
    val decidedByName: String?,
    val decisionReason: String?,
    val voidedAt: Instant?,
    val voidedByName: String?,
    val voidReason: String?,
    // Which payments' credit it used.
    val allocations: List<RefundAllocationResponse>,
) {
    companion object {
        fun from(refund: MaintenanceRefund, names: Map<Long, String>) = RefundResponse(
            id = refund.id!!,
            refundNumber = refund.refundNumber,
            societyId = refund.society.id!!,
            flatId = refund.flat.id!!,
            wingName = refund.flat.wing.name,
            flatNumber = refund.flat.flatNumber,
            amount = refund.amount,
            paidOn = refund.paidOn,
            method = refund.method,
            paidFromAccountId = refund.paidFromAccountId,
            reference = refund.reference,
            reason = refund.reason,
            status = refund.status,
            createdAt = refund.createdAt,
            createdBy = refund.createdBy,
            createdByName = names[refund.createdBy],
            decidedAt = refund.decidedAt,
            decidedByName = refund.decidedBy?.let(names::get),
            decisionReason = refund.decisionReason,
            voidedAt = refund.voidedAt,
            voidedByName = refund.voidedBy?.let(names::get),
            voidReason = refund.voidReason,
            allocations = refund.allocations.map { RefundAllocationResponse(it.paymentId, it.amount) },
        )
    }
}

data class FlatCreditResponse(val flatId: Long, val credit: BigDecimal)

data class RefundReasonRequest(@field:Size(max = 500) val reason: String? = null)
