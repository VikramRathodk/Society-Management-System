package org.devvikram.societymanagement.subscription.repository

import jakarta.persistence.LockModeType
import org.devvikram.societymanagement.subscription.model.RazorpayOrder
import org.devvikram.societymanagement.subscription.model.RazorpayOrderStatus
import org.devvikram.societymanagement.subscription.model.RazorpayWebhookEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

interface RazorpayOrderRepository {
    fun save(order: RazorpayOrder): RazorpayOrder
    // Locked, so the checkout return and the webhook for one payment record it once.
    fun lockByOrderId(orderId: String): RazorpayOrder?
    fun findOpen(invoiceId: Long, amount: Long): RazorpayOrder?
}

interface RazorpayOrderJpaRepository : JpaRepository<RazorpayOrder, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM RazorpayOrder o WHERE o.orderId = :orderId")
    fun findLockedByOrderId(orderId: String): RazorpayOrder?

    fun findFirstByInvoiceIdAndAmountAndStatusOrderByIdDesc(invoiceId: Long, amount: Long, status: RazorpayOrderStatus): RazorpayOrder?
}

@Repository
class RazorpayOrderRepositoryImpl(private val jpa: RazorpayOrderJpaRepository) : RazorpayOrderRepository {
    override fun save(order: RazorpayOrder): RazorpayOrder = jpa.saveAndFlush(order)
    override fun lockByOrderId(orderId: String): RazorpayOrder? = jpa.findLockedByOrderId(orderId)
    override fun findOpen(invoiceId: Long, amount: Long): RazorpayOrder? =
        jpa.findFirstByInvoiceIdAndAmountAndStatusOrderByIdDesc(invoiceId, amount, RazorpayOrderStatus.CREATED)
}

interface RazorpayWebhookEventRepository {
    fun save(event: RazorpayWebhookEvent): RazorpayWebhookEvent
    fun existsByEventId(eventId: String): Boolean
}

interface RazorpayWebhookEventJpaRepository : JpaRepository<RazorpayWebhookEvent, Long> {
    fun existsByEventId(eventId: String): Boolean
}

@Repository
class RazorpayWebhookEventRepositoryImpl(private val jpa: RazorpayWebhookEventJpaRepository) : RazorpayWebhookEventRepository {
    override fun save(event: RazorpayWebhookEvent): RazorpayWebhookEvent = jpa.saveAndFlush(event)
    override fun existsByEventId(eventId: String): Boolean = jpa.existsByEventId(eventId)
}
