package org.devvikram.societymanagement.maintenance.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.devvikram.societymanagement.maintenance.model.MaintenancePayment
import org.devvikram.societymanagement.maintenance.model.PaymentAllocation
import org.devvikram.societymanagement.maintenance.model.PaymentMethod
import org.devvikram.societymanagement.maintenance.model.PaymentStatus
import org.devvikram.societymanagement.maintenance.repository.FlatDues
import org.devvikram.societymanagement.maintenance.service.OpeningBalanceEntry
import org.devvikram.societymanagement.maintenance.service.OpeningBalancesCommand
import org.devvikram.societymanagement.maintenance.service.RecordPaymentCommand
import org.devvikram.societymanagement.maintenance.service.ReminderResult
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// ---- Payments ----

data class RecordPaymentRequest(
    @field:NotNull val flatId: Long?,
    @field:NotNull @field:DecimalMin("0.01") @field:Digits(integer = 10, fraction = 2) val amount: BigDecimal?,
    @field:NotNull val paidOn: LocalDate?,
    @field:NotNull val method: PaymentMethod?,
    @field:Size(max = 100) val reference: String? = null,
    @field:Size(max = 2000) val note: String? = null,
    // Pay this bill first instead of the oldest.
    val billId: Long? = null,
) {
    fun toCommand() = RecordPaymentCommand(flatId!!, amount!!, paidOn!!, method!!, reference, note, billId)
}

data class AllocationResponse(val billId: Long, val billNumber: String, val amount: BigDecimal) {
    companion object {
        fun from(allocation: PaymentAllocation) =
            AllocationResponse(allocation.bill.id!!, allocation.bill.billNumber, allocation.amount)
    }
}

data class PaymentResponse(
    val id: Long,
    val receiptNumber: String,
    val societyId: Long,
    val flatId: Long,
    val wingName: String,
    val flatNumber: String,
    val amount: BigDecimal,
    val paidOn: LocalDate,
    val method: PaymentMethod,
    val reference: String?,
    val note: String?,
    val status: PaymentStatus,
    val recordedAt: Instant?,
    val recordedByName: String,
    val voidedAt: Instant?,
    val voidedByName: String?,
    val voidReason: String?,
    // Which bills it paid; only on the single-payment endpoint (the receipt), null in lists.
    val allocations: List<AllocationResponse>?,
) {
    companion object {
        fun from(payment: MaintenancePayment, withAllocations: Boolean = false) = PaymentResponse(
            id = payment.id!!,
            receiptNumber = payment.receiptNumber,
            societyId = payment.society.id!!,
            flatId = payment.flat.id!!,
            wingName = payment.flat.wing.name,
            flatNumber = payment.flat.flatNumber,
            amount = payment.amount,
            paidOn = payment.paidOn,
            method = payment.method,
            reference = payment.reference,
            note = payment.note,
            status = payment.status,
            recordedAt = payment.recordedAt,
            recordedByName = payment.recordedBy.name,
            voidedAt = payment.voidedAt,
            voidedByName = payment.voidedBy?.name,
            voidReason = payment.voidReason,
            allocations = if (withAllocations) payment.allocations.map(AllocationResponse::from) else null,
        )
    }
}

// A payment as seen from one of the bills it paid.
data class BillPaymentResponse(
    val paymentId: Long,
    val receiptNumber: String,
    val paidOn: LocalDate,
    val method: PaymentMethod,
    // The part of the payment that went to this bill.
    val amount: BigDecimal,
) {
    companion object {
        fun from(allocation: PaymentAllocation) = BillPaymentResponse(
            paymentId = allocation.payment.id!!,
            receiptNumber = allocation.payment.receiptNumber,
            paidOn = allocation.payment.paidOn,
            method = allocation.payment.method,
            amount = allocation.amount,
        )
    }
}

// ---- Opening balances ----

data class OpeningBalanceEntryRequest(
    @field:NotNull val flatId: Long?,
    @field:NotNull @field:DecimalMin("0.01") @field:Digits(integer = 10, fraction = 2) val amount: BigDecimal?,
    @field:Size(max = 200) val note: String? = null,
)

data class OpeningBalancesRequest(
    @field:NotNull val asOf: LocalDate?,
    @field:NotNull val dueDate: LocalDate?,
    @field:NotEmpty @field:Valid val entries: List<OpeningBalanceEntryRequest>,
) {
    fun toCommand() = OpeningBalancesCommand(asOf!!, dueDate!!, entries.map { OpeningBalanceEntry(it.flatId!!, it.amount!!, it.note) })
}

// ---- Reports ----

data class FlatDuesResponse(
    val flatId: Long,
    val wingName: String,
    val flatNumber: String,
    val openBills: Long,
    val outstanding: BigDecimal,
    // Past the due date plus the society's grace days.
    val overdue: BigDecimal,
    val oldestDueDate: LocalDate,
) {
    companion object {
        fun from(dues: FlatDues) = FlatDuesResponse(
            dues.flatId, dues.wingName, dues.flatNumber, dues.openBills,
            dues.outstanding.setScale(2), dues.overdue.setScale(2), dues.oldestDueDate,
        )
    }
}

// flatIds omitted: remind every defaulting flat.
data class RemindRequest(val flatIds: Set<Long>? = null)

data class ReminderResponse(val flatCount: Int, val totalOverdue: BigDecimal) {
    companion object {
        fun from(result: ReminderResult) = ReminderResponse(result.flatCount, result.totalOverdue)
    }
}
