package org.devvikram.societymanagement.subscription.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class RazorpayOrderStatus { CREATED, PAID, FAILED }

// A Razorpay order for an invoice's amount due (in paise); Checkout pays it, and the payment is recorded
// once verified (checkout signature or the payment.captured webhook, whichever comes first).
@Entity
@Table(name = "razorpay_order")
class RazorpayOrder(
    @Column(name = "invoice_id", nullable = false) var invoiceId: Long,
    @Column(name = "order_id", nullable = false) var orderId: String,
    @Column(nullable = false) var amount: Long,
    @Column(name = "created_by") var createdBy: Long?,
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: RazorpayOrderStatus = RazorpayOrderStatus.CREATED

    @Column(name = "payment_id")
    var paymentId: String? = null

    var error: String? = null

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
}

// A webhook delivery with a valid signature, kept once per event id.
@Entity
@Table(name = "razorpay_webhook_event")
class RazorpayWebhookEvent(
    @Column(name = "event_id", nullable = false) var eventId: String,
    @Column(nullable = false) var type: String,
    @Column(nullable = false) var payload: String,
    @Column(name = "received_at", nullable = false) var receivedAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    var outcome: String? = null

    @Column(name = "processed_at")
    var processedAt: Instant? = null
}
