package org.devvikram.societymanagement.subscription.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class InvoiceStatus { ISSUED, PARTLY_PAID, PAID }

// The platform's invoice to a society for one billing period. Everything printed is frozen at issue.
@Entity
@Table(name = "platform_invoice")
class PlatformInvoice(
    @Column(nullable = false) var number: String,
    @Column(name = "society_id", nullable = false) var societyId: Long,
    @Column(name = "subscription_id", nullable = false) var subscriptionId: Long,
    @Column(name = "period_start", nullable = false) var periodStart: LocalDate,
    @Column(name = "period_end", nullable = false) var periodEnd: LocalDate,
    @Column(name = "plan_code", nullable = false) var planCode: String,
    @Column(name = "plan_name", nullable = false) var planName: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var cycle: BillingCycle,
    @Column(nullable = false) var flats: Int,
    @Column(name = "rate_per_flat", nullable = false) var ratePerFlat: BigDecimal,
    @Column(name = "minimum_charge", nullable = false) var minimumCharge: BigDecimal,
    @Column(nullable = false) var amount: BigDecimal,
    @Column(name = "discount_percent", nullable = false) var discountPercent: Int,
    @Column(name = "discount_amount", nullable = false) var discountAmount: BigDecimal,
    @Column(name = "taxable_value", nullable = false) var taxableValue: BigDecimal,
    @Column(nullable = false) var cgst: BigDecimal,
    @Column(nullable = false) var sgst: BigDecimal,
    @Column(nullable = false) var igst: BigDecimal,
    @Column(nullable = false) var total: BigDecimal,
    @Column(name = "issued_on", nullable = false) var issuedOn: LocalDate,
    @Column(name = "due_on", nullable = false) var dueOn: LocalDate,
    @Column(name = "society_name", nullable = false) var societyName: String,
    @Column(name = "society_address") var societyAddress: String?,
    @Column(name = "society_state") var societyState: String?,
    @Column(name = "platform_gstin") var platformGstin: String?,
    @Column(name = "created_by") var createdBy: Long?,
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "paid_amount", nullable = false)
    var paidAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "credited_amount", nullable = false)
    var creditedAmount: BigDecimal = BigDecimal.ZERO

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: InvoiceStatus = InvoiceStatus.ISSUED

    // The day of the last payment reminder (the billing run never reminds twice in a day).
    @Column(name = "last_reminded_on")
    var lastRemindedOn: LocalDate? = null

    // What is still to pay.
    val balance: BigDecimal get() = total.subtract(paidAmount).subtract(creditedAmount)

    val tax: BigDecimal get() = cgst.add(sgst).add(igst)
}

enum class PaymentMode { RAZORPAY, BANK_TRANSFER, UPI, CHEQUE, CASH }

@Entity
@Table(name = "platform_payment")
class PlatformPayment(
    @Column(name = "receipt_number", nullable = false) var receiptNumber: String,
    @Column(name = "invoice_id", nullable = false) var invoiceId: Long,
    @Column(nullable = false) var amount: BigDecimal,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var mode: PaymentMode,
    var reference: String?,
    @Column(name = "paid_on", nullable = false) var paidOn: LocalDate,
    @Column(name = "gateway_payment_id") var gatewayPaymentId: String?,
    @Column(name = "recorded_by") var recordedBy: Long?,
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

// Lowers what an invoice asks for (a mistake, a goodwill credit).
@Entity
@Table(name = "platform_credit_note")
class PlatformCreditNote(
    @Column(nullable = false) var number: String,
    @Column(name = "invoice_id", nullable = false) var invoiceId: Long,
    @Column(nullable = false) var amount: BigDecimal,
    @Column(nullable = false) var reason: String,
    @Column(name = "created_by") var createdBy: Long?,
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
