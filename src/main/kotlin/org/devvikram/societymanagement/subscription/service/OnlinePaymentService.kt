package org.devvikram.societymanagement.subscription.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.subscription.model.PaymentMode
import org.devvikram.societymanagement.subscription.model.PlatformPayment
import org.devvikram.societymanagement.subscription.model.RazorpayOrder
import org.devvikram.societymanagement.subscription.model.RazorpayOrderStatus
import org.devvikram.societymanagement.subscription.model.RazorpayWebhookEvent
import org.devvikram.societymanagement.subscription.repository.InvoiceRepository
import org.devvikram.societymanagement.subscription.repository.PaymentRepository
import org.devvikram.societymanagement.subscription.repository.RazorpayOrderRepository
import org.devvikram.societymanagement.subscription.repository.RazorpayWebhookEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant

// What the web needs to open Razorpay Checkout for an invoice.
data class CheckoutOrder(
    val keyId: String,
    val orderId: String,
    // In paise.
    val amount: Long,
    val currency: String,
    val invoiceId: Long,
    val invoiceNumber: String,
    val merchantName: String,
)

// The outcome of a verified Razorpay payment: recorded now, recorded before (the other path got there
// first), or not recorded and why (needs the super admin: refund in the Razorpay dashboard).
data class CaptureResult(val payment: PlatformPayment?, val alreadyRecorded: Boolean, val problem: String?)

// Paying platform invoices online. A payment is recorded only once verified — by the Checkout signature
// when the browser comes back, or by the signed payment.captured webhook (the source of truth, so a
// closed tab still gets recorded) — and only once (keyed by Razorpay's payment id, with the order locked).
@Service
class OnlinePaymentService(
    private val settings: RazorpaySettings,
    private val client: RazorpayClient,
    private val orderRepository: RazorpayOrderRepository,
    private val webhookRepository: RazorpayWebhookEventRepository,
    private val invoiceRepository: InvoiceRepository,
    private val paymentRepository: PaymentRepository,
    private val invoiceService: InvoiceService,
    private val subscriptionService: SubscriptionService,
    private val jsonMapper: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // An order for what the invoice still asks for; an open order for the same amount is reused.
    @Transactional
    fun createOrder(invoiceId: Long, userId: Long): CheckoutOrder {
        val invoice = invoiceRepository.lock(invoiceId) ?: throw NoSuchElementException("Invoice $invoiceId not found")
        if (invoice.balance.signum() <= 0) throw ConflictException("Invoice ${invoice.number} is already settled")
        val amount = paise(invoice.balance)
        val order = orderRepository.findOpen(invoiceId, amount)
            ?: orderRepository.save(
                RazorpayOrder(
                    invoiceId = invoiceId,
                    orderId = client.createOrder(amount, invoice.number, mapOf("invoiceId" to invoiceId.toString(), "societyId" to invoice.societyId.toString())),
                    amount = amount,
                    createdBy = userId,
                )
            )
        return CheckoutOrder(settings.keyId, order.orderId, order.amount, "INR", invoiceId, invoice.number, invoiceService.platform.legalName)
    }

    // The browser is back from Checkout with Razorpay's signature over "orderId|paymentId".
    @Transactional
    fun verifyCheckout(invoiceId: Long, orderId: String, paymentId: String, signature: String): CaptureResult {
        val order = orderRepository.lockByOrderId(orderId) ?: throw NoSuchElementException("Order $orderId not found")
        require(order.invoiceId == invoiceId) { "Order $orderId is for another invoice" }
        require(settings.checkoutSignatureValid(orderId, paymentId, signature)) { "The payment couldn't be verified" }
        return capture(order, paymentId, order.amount)
    }

    // A webhook delivery: refused unless signed with the webhook secret; each event handled once.
    // Answers what was done (kept with the event).
    @Transactional
    fun webhook(body: String, signature: String?, eventIdHeader: String?): String {
        require(settings.webhookSignatureValid(body, signature)) { "Invalid webhook signature" }
        val eventId = eventIdHeader?.trim()?.ifEmpty { null } ?: sha256(body)
        if (webhookRepository.existsByEventId(eventId)) return "duplicate"
        val root = jsonMapper.readTree(body)
        val type = root.path("event").asString("")
        val event = webhookRepository.save(RazorpayWebhookEvent(eventId, type.ifEmpty { "unknown" }, body))
        val outcome = try {
            handle(type, root.path("payload").path("payment").path("entity"))
        } catch (e: RuntimeException) {
            log.warn("Razorpay webhook {} ({}) failed", eventId, type, e)
            "error: ${e.message}"
        }
        event.outcome = outcome.take(500)
        event.processedAt = Instant.now()
        webhookRepository.save(event)
        return outcome
    }

    private fun handle(type: String, payment: JsonNode): String {
        val paymentId = payment.path("id").asString("")
        val orderId = payment.path("order_id").asString("")
        return when (type) {
            "payment.captured" -> {
                val order = orderRepository.lockByOrderId(orderId) ?: return "unknown order $orderId"
                val result = capture(order, paymentId, payment.path("amount").asLong(0))
                when {
                    result.problem != null -> result.problem
                    result.alreadyRecorded -> "already recorded"
                    else -> "recorded ${result.payment!!.receiptNumber}"
                }
            }
            "payment.failed" -> {
                val order = orderRepository.lockByOrderId(orderId) ?: return "unknown order $orderId"
                if (order.status == RazorpayOrderStatus.CREATED) {
                    order.status = RazorpayOrderStatus.FAILED
                    order.paymentId = paymentId
                    order.error = payment.path("error_description").asString("").take(500).ifEmpty { null }
                    order.updatedAt = Instant.now()
                    orderRepository.save(order)
                }
                "order failed"
            }
            else -> "ignored"
        }
    }

    // Records a verified payment once. The order is locked by the caller. Checks are made before recording,
    // so nothing inside the recording transaction fails.
    private fun capture(order: RazorpayOrder, paymentId: String, amountPaise: Long): CaptureResult {
        require(paymentId.isNotBlank()) { "The payment id is missing" }
        paymentRepository.findByGatewayId(paymentId)?.let { return CaptureResult(it, true, null) }
        if (amountPaise != order.amount) {
            return problem(order, paymentId, "Amount ${rupees(amountPaise)} doesn't match order ${order.orderId} (${rupees(order.amount)}); refund it in Razorpay")
        }
        val invoice = invoiceRepository.findById(order.invoiceId) ?: throw NoSuchElementException("Invoice ${order.invoiceId} not found")
        val amount = rupees(amountPaise)
        if (amount > invoice.balance) {
            return problem(order, paymentId, "Invoice ${invoice.number} had only ₹${invoice.balance.toPlainString()} left to pay (paid another way); refund ₹${amount.toPlainString()} in Razorpay")
        }
        val recorded = invoiceService.recordPayment(
            order.invoiceId, amount, PaymentMode.RAZORPAY, paymentId, subscriptionService.today(), null, gatewayPaymentId = paymentId,
        )
        order.status = RazorpayOrderStatus.PAID
        order.paymentId = paymentId
        order.updatedAt = Instant.now()
        orderRepository.save(order)
        return CaptureResult(recorded, false, null)
    }

    private fun problem(order: RazorpayOrder, paymentId: String, message: String): CaptureResult {
        log.warn("Razorpay payment {} not recorded: {}", paymentId, message)
        order.paymentId = paymentId
        order.error = message.take(500)
        order.updatedAt = Instant.now()
        orderRepository.save(order)
        return CaptureResult(null, false, message)
    }

    private fun paise(rupees: BigDecimal): Long = rupees.movePointRight(2).longValueExact()

    private fun rupees(paise: Long): BigDecimal = BigDecimal.valueOf(paise, 2)

    private fun sha256(body: String): String =
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
}
