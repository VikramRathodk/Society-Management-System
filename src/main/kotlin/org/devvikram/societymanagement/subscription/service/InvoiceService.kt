package org.devvikram.societymanagement.subscription.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.subscription.model.BillingCycle
import org.devvikram.societymanagement.subscription.model.InvoiceStatus
import org.devvikram.societymanagement.subscription.model.PaymentMode
import org.devvikram.societymanagement.subscription.model.PlatformCreditNote
import org.devvikram.societymanagement.subscription.model.PlatformInvoice
import org.devvikram.societymanagement.subscription.model.PlatformPayment
import org.devvikram.societymanagement.subscription.model.Subscription
import org.devvikram.societymanagement.subscription.model.SubscriptionEvent
import org.devvikram.societymanagement.subscription.model.SubscriptionEventKind
import org.devvikram.societymanagement.subscription.model.SubscriptionStatus
import org.devvikram.societymanagement.subscription.repository.CreditNoteRepository
import org.devvikram.societymanagement.subscription.repository.InvoiceRepository
import org.devvikram.societymanagement.subscription.repository.PaymentRepository
import org.devvikram.societymanagement.subscription.repository.PlatformCounter
import org.devvikram.societymanagement.subscription.repository.SubscriptionEventRepository
import org.devvikram.societymanagement.subscription.repository.SubscriptionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

// The platform's details printed on invoices (deployment settings).
data class PlatformBilling(
    val gstin: String?,
    val legalName: String,
    val address: String?,
    val state: String?,
    val gstPercent: BigDecimal,
    val dueDays: Long,
    val prefix: String,
)

data class InvoiceView(
    val invoice: PlatformInvoice,
    val payments: List<PlatformPayment>,
    val creditNotes: List<PlatformCreditNote>,
)

// Invoices for each billing period, with GST; payments recorded against them with receipts; credit notes.
@Service
class InvoiceService(
    private val subscriptionService: SubscriptionService,
    private val subscriptionRepository: SubscriptionRepository,
    private val eventRepository: SubscriptionEventRepository,
    private val invoiceRepository: InvoiceRepository,
    private val paymentRepository: PaymentRepository,
    private val creditNoteRepository: CreditNoteRepository,
    private val counter: PlatformCounter,
    private val societyService: SocietyService,
    private val events: ApplicationEventPublisher,
    @Value("\${app.billing.platform-gstin:}") gstin: String,
    @Value("\${app.billing.legal-name:Sahvasa}") legalName: String,
    @Value("\${app.billing.address:}") address: String,
    @Value("\${app.billing.state:}") state: String,
    @Value("\${app.billing.gst-percent:18}") gstPercent: BigDecimal,
    @Value("\${app.billing.invoice-due-days:15}") dueDays: Long,
    @Value("\${app.billing.number-prefix:SAH}") prefix: String,
) {
    val platform = PlatformBilling(
        gstin = gstin.trim().ifEmpty { null },
        legalName = legalName,
        address = address.trim().ifEmpty { null },
        state = state.trim().ifEmpty { null },
        gstPercent = gstPercent,
        dueDays = dueDays,
        prefix = prefix,
    )

    // ---- Issuing ----

    // Issues the invoice for the subscription's next period once it is due (on or after its first day).
    // A pending plan or cycle change applies now; a discount counts down by one period; a trial becomes
    // ACTIVE. Answers null when nothing is due yet.
    @Transactional
    // `today` is passed in (not defaulted): a default value would be computed on the transactional proxy.
    fun issueDue(societyId: Long, userId: Long?, today: LocalDate): PlatformInvoice? {
        val subscription = subscriptionRepository.lockBySociety(societyId) ?: throw NoSuchElementException("Society $societyId has no subscription")
        if (subscription.status == SubscriptionStatus.CANCELLED) throw ConflictException("The subscription is cancelled")
        // Periods that cost nothing (a free plan) issue no invoice; they only move the subscription on.
        while (!today.isBefore(subscription.nextInvoiceOn)) {
            issue(subscription, userId, today)?.let { return it }
        }
        return null
    }

    private fun issue(subscription: Subscription, userId: Long?, today: LocalDate): PlatformInvoice? {
        subscription.pendingPlanId?.let { subscription.planId = it }
        subscription.pendingCycle?.let { subscription.cycle = it }
        if (subscription.pendingPlanId != null || subscription.pendingCycle != null) {
            record(subscription, SubscriptionEventKind.PLAN_CHANGED, null, "${subscriptionService.plan(subscription.planId).code} ${subscription.cycle}", "Applied with the new period", userId)
        }
        subscription.pendingPlanId = null
        subscription.pendingCycle = null

        val plan = subscriptionService.plan(subscription.planId)
        val society = societyService.getById(subscription.societyId)
        val flats = subscriptionService.flatCount(subscription.societyId)
        val start = subscription.nextInvoiceOn
        val end = subscriptionService.periodEnd(start, subscription.cycle)
        val amount = subscriptionService.charge(plan, subscription.cycle, flats)
        if (amount.signum() == 0) {
            startPeriod(subscription, start, end, userId)
            return null
        }
        val discountPercent = if (subscription.discountPeriodsLeft > 0) subscription.discountPercent else 0
        val discount = money(amount.multiply(BigDecimal(discountPercent)).divide(HUNDRED))
        val taxable = amount.subtract(discount)
        val (cgst, sgst, igst) = tax(taxable, society.state)
        val invoice = invoiceRepository.save(
            PlatformInvoice(
                number = number("INV", today),
                societyId = subscription.societyId,
                subscriptionId = subscription.id!!,
                periodStart = start,
                periodEnd = end,
                planCode = plan.code,
                planName = plan.name,
                cycle = subscription.cycle,
                flats = flats,
                basePrice = plan.price(subscription.cycle),
                includedFlats = plan.includedFlats,
                extraFlats = plan.extraFlats(flats),
                extraFlatRate = plan.extraFlatRate(subscription.cycle),
                amount = amount,
                discountPercent = discountPercent,
                discountAmount = discount,
                taxableValue = taxable,
                cgst = cgst,
                sgst = sgst,
                igst = igst,
                total = taxable.add(cgst).add(sgst).add(igst),
                issuedOn = today,
                dueOn = today.plusDays(platform.dueDays),
                societyName = society.name,
                societyAddress = listOfNotNull(society.address, society.city, society.state, society.pincode).joinToString(", ").ifEmpty { null },
                societyState = society.state,
                platformGstin = platform.gstin,
                createdBy = userId,
            )
        )
        if (subscription.discountPeriodsLeft > 0) {
            subscription.discountPeriodsLeft -= 1
            if (subscription.discountPeriodsLeft == 0) subscription.discountPercent = 0
        }
        startPeriod(subscription, start, end, userId)
        events.publishEvent(InvoiceIssued(invoice.id!!, invoice.societyId, invoice.number, invoice.total, invoice.dueOn, subscription.billingContactId))
        return invoice
    }

    // The subscription moves into the period just invoiced (or that cost nothing); a trial ends.
    private fun startPeriod(subscription: Subscription, start: LocalDate, end: LocalDate, userId: Long?) {
        if (subscription.status == SubscriptionStatus.TRIAL) {
            subscription.status = SubscriptionStatus.ACTIVE
            record(subscription, SubscriptionEventKind.STATUS_CHANGED, SubscriptionStatus.TRIAL.name, SubscriptionStatus.ACTIVE.name, "Trial ended", userId)
        }
        subscription.currentPeriodStart = start
        subscription.currentPeriodEnd = end
        subscription.nextInvoiceOn = end.plusDays(1)
        subscriptionRepository.save(subscription)
    }

    // GST on the taxable value: CGST + SGST when the society is in the platform's state, IGST otherwise;
    // none while the platform has no GSTIN.
    fun tax(taxable: BigDecimal, societyState: String?): Triple<BigDecimal, BigDecimal, BigDecimal> {
        if (platform.gstin == null) return Triple(BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2))
        val sameState = platform.state != null && societyState != null && platform.state.equals(societyState.trim(), ignoreCase = true)
        return if (sameState) {
            val half = money(taxable.multiply(platform.gstPercent).divide(HUNDRED).divide(BigDecimal(2)))
            Triple(half, half, BigDecimal.ZERO.setScale(2))
        } else {
            Triple(BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), money(taxable.multiply(platform.gstPercent).divide(HUNDRED)))
        }
    }

    // ---- Payments and credit notes ----

    // A payment against the invoice (at most what is still to pay), with a receipt.
    @Transactional
    fun recordPayment(
        invoiceId: Long,
        amount: BigDecimal,
        mode: PaymentMode,
        reference: String?,
        paidOn: LocalDate,
        userId: Long?,
        gatewayPaymentId: String? = null,
    ): PlatformPayment {
        val invoice = lock(invoiceId)
        val paid = money(amount)
        require(paid > BigDecimal.ZERO) { "The amount must be more than zero" }
        if (invoice.balance.signum() == 0) throw ConflictException("Invoice ${invoice.number} is already settled")
        require(paid <= invoice.balance) { "Invoice ${invoice.number} has ₹${invoice.balance.toPlainString()} to pay" }
        require(!paidOn.isAfter(subscriptionService.today())) { "The payment date can't be in the future" }
        require((reference?.length ?: 0) <= 100) { "The reference can be at most 100 characters" }
        val payment = paymentRepository.save(
            PlatformPayment(number("RCPT", paidOn), invoice.id!!, paid, mode, reference?.trim()?.ifEmpty { null }, paidOn, gatewayPaymentId, userId)
        )
        invoice.paidAmount = invoice.paidAmount.add(paid)
        settle(invoice)
        events.publishEvent(PaymentReceived(invoice.id!!, invoice.societyId, invoice.number, payment.receiptNumber, paid))
        return payment
    }

    // Lowers what the invoice asks for (at most what is still to pay).
    @Transactional
    fun creditNote(invoiceId: Long, amount: BigDecimal, reason: String, userId: Long): PlatformCreditNote {
        val invoice = lock(invoiceId)
        val credit = money(amount)
        require(credit > BigDecimal.ZERO) { "The amount must be more than zero" }
        require(credit <= invoice.balance) { "Invoice ${invoice.number} has ₹${invoice.balance.toPlainString()} to pay" }
        val clean = reason.trim()
        require(clean.isNotEmpty() && clean.length <= 500) { "Give a reason of at most 500 characters" }
        val note = creditNoteRepository.save(PlatformCreditNote(number("CN", subscriptionService.today()), invoice.id!!, credit, clean, userId))
        invoice.creditedAmount = invoice.creditedAmount.add(credit)
        settle(invoice)
        return note
    }

    // Paid once nothing is left; a society with no overdue balance left is active again.
    private fun settle(invoice: PlatformInvoice) {
        invoice.status = when {
            invoice.balance.signum() == 0 -> InvoiceStatus.PAID
            invoice.paidAmount.signum() > 0 -> InvoiceStatus.PARTLY_PAID
            else -> InvoiceStatus.ISSUED
        }
        invoiceRepository.save(invoice)
        val subscription = subscriptionRepository.lockBySociety(invoice.societyId) ?: return
        val overdue = invoiceRepository.findBySociety(invoice.societyId)
            .any { it.balance.signum() > 0 && it.dueOn.isBefore(subscriptionService.today()) }
        if (!overdue && (subscription.status == SubscriptionStatus.PAST_DUE || subscription.status == SubscriptionStatus.SUSPENDED)) {
            val from = subscription.status
            subscription.status = SubscriptionStatus.ACTIVE
            subscriptionRepository.save(subscription)
            record(subscription, SubscriptionEventKind.STATUS_CHANGED, from.name, SubscriptionStatus.ACTIVE.name, "Paid up", null)
            events.publishEvent(SubscriptionStatusChanged(invoice.societyId, from, SubscriptionStatus.ACTIVE, subscription.billingContactId))
        }
    }

    // ---- Reads ----

    fun view(invoiceId: Long): InvoiceView {
        val invoice = get(invoiceId)
        return InvoiceView(invoice, paymentRepository.findByInvoice(invoiceId), creditNoteRepository.findByInvoice(invoiceId))
    }

    fun get(invoiceId: Long): PlatformInvoice =
        invoiceRepository.findById(invoiceId) ?: throw NoSuchElementException("Invoice $invoiceId not found")

    fun ofSociety(societyId: Long): List<PlatformInvoice> = invoiceRepository.findBySociety(societyId)

    fun all(): List<PlatformInvoice> = invoiceRepository.findAll()

    // ---- Helpers ----

    // SAH/2026-27/0042 (INV), SAH-R/… (RCPT), SAH-CN/… (CN): per kind and April–March year.
    private fun number(kind: String, on: LocalDate): String {
        val first = if (on.monthValue >= 4) on.year else on.year - 1
        val year = "$first-${((first + 1) % 100).toString().padStart(2, '0')}"
        val prefix = when (kind) {
            "INV" -> platform.prefix
            "RCPT" -> "${platform.prefix}-R"
            else -> "${platform.prefix}-CN"
        }
        return "$prefix/$year/${counter.next(kind, year).toString().padStart(4, '0')}"
    }

    private fun lock(invoiceId: Long): PlatformInvoice =
        invoiceRepository.lock(invoiceId) ?: throw NoSuchElementException("Invoice $invoiceId not found")

    private fun record(subscription: Subscription, kind: SubscriptionEventKind, from: String?, to: String?, note: String?, userId: Long?) {
        eventRepository.save(SubscriptionEvent(subscription.id!!, kind, from, to, note, userId))
    }

    private fun money(value: BigDecimal) = value.setScale(2, RoundingMode.HALF_UP)

    companion object {
        private val HUNDRED = BigDecimal(100)
    }
}
