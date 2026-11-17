package org.devvikram.societymanagement.subscription.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.devvikram.societymanagement.subscription.model.BillingCycle
import org.devvikram.societymanagement.subscription.model.InvoiceStatus
import org.devvikram.societymanagement.subscription.model.PaymentMode
import org.devvikram.societymanagement.subscription.model.PlatformCreditNote
import org.devvikram.societymanagement.subscription.model.PlatformInvoice
import org.devvikram.societymanagement.subscription.model.PlatformPayment
import org.devvikram.societymanagement.subscription.service.InvoiceView
import org.devvikram.societymanagement.subscription.service.PlatformBilling
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class RecordPaymentRequest(
    @field:NotNull val amount: BigDecimal?,
    @field:NotNull val mode: PaymentMode?,
    val reference: String? = null,
    val paidOn: LocalDate? = null,
)

data class CreditNoteRequest(@field:NotNull val amount: BigDecimal?, @field:NotBlank val reason: String?)

data class InvoiceSummaryResponse(
    val id: Long,
    val number: String,
    val societyId: Long,
    val societyName: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val planName: String,
    val cycle: BillingCycle,
    val total: BigDecimal,
    val balance: BigDecimal,
    val status: InvoiceStatus,
    val issuedOn: LocalDate,
    val dueOn: LocalDate,
    val overdue: Boolean,
) {
    companion object {
        fun from(i: PlatformInvoice, today: LocalDate) = InvoiceSummaryResponse(
            i.id!!, i.number, i.societyId, i.societyName, i.periodStart, i.periodEnd, i.planName, i.cycle, i.total, i.balance,
            i.status, i.issuedOn, i.dueOn, i.balance.signum() > 0 && i.dueOn.isBefore(today),
        )
    }
}

data class PaymentResponse(
    val id: Long,
    val receiptNumber: String,
    val amount: BigDecimal,
    val mode: PaymentMode,
    val reference: String?,
    val paidOn: LocalDate,
    val createdAt: Instant,
) {
    companion object {
        fun from(p: PlatformPayment) = PaymentResponse(p.id!!, p.receiptNumber, p.amount, p.mode, p.reference, p.paidOn, p.createdAt)
    }
}

data class CreditNoteResponse(val id: Long, val number: String, val amount: BigDecimal, val reason: String, val createdAt: Instant) {
    companion object {
        fun from(c: PlatformCreditNote) = CreditNoteResponse(c.id!!, c.number, c.amount, c.reason, c.createdAt)
    }
}

data class SellerResponse(val legalName: String, val gstin: String?, val address: String?, val state: String?)

// Everything a printable invoice needs.
data class InvoiceResponse(
    val summary: InvoiceSummaryResponse,
    val seller: SellerResponse,
    val societyAddress: String?,
    val societyState: String?,
    val planCode: String,
    val flats: Int,
    val ratePerFlat: BigDecimal,
    val minimumCharge: BigDecimal,
    val amount: BigDecimal,
    val discountPercent: Int,
    val discountAmount: BigDecimal,
    val taxableValue: BigDecimal,
    val cgst: BigDecimal,
    val sgst: BigDecimal,
    val igst: BigDecimal,
    val gstPercent: BigDecimal,
    val total: BigDecimal,
    val paidAmount: BigDecimal,
    val creditedAmount: BigDecimal,
    val payments: List<PaymentResponse>,
    val creditNotes: List<CreditNoteResponse>,
) {
    companion object {
        fun from(v: InvoiceView, platform: PlatformBilling, today: LocalDate): InvoiceResponse {
            val i = v.invoice
            return InvoiceResponse(
                summary = InvoiceSummaryResponse.from(i, today),
                seller = SellerResponse(platform.legalName, i.platformGstin, platform.address, platform.state),
                societyAddress = i.societyAddress,
                societyState = i.societyState,
                planCode = i.planCode,
                flats = i.flats,
                ratePerFlat = i.ratePerFlat,
                minimumCharge = i.minimumCharge,
                amount = i.amount,
                discountPercent = i.discountPercent,
                discountAmount = i.discountAmount,
                taxableValue = i.taxableValue,
                cgst = i.cgst,
                sgst = i.sgst,
                igst = i.igst,
                gstPercent = if (i.platformGstin != null) platform.gstPercent else BigDecimal.ZERO,
                total = i.total,
                paidAmount = i.paidAmount,
                creditedAmount = i.creditedAmount,
                payments = v.payments.map(PaymentResponse::from),
                creditNotes = v.creditNotes.map(CreditNoteResponse::from),
            )
        }
    }
}

data class RazorpayVerifyRequest(
    @field:jakarta.validation.constraints.NotBlank val orderId: String?,
    @field:jakarta.validation.constraints.NotBlank val paymentId: String?,
    @field:jakarta.validation.constraints.NotBlank val signature: String?,
)
