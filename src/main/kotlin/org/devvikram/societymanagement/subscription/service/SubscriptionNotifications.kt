package org.devvikram.societymanagement.subscription.service

import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.notification.PushNotifier
import org.devvikram.societymanagement.subscription.model.SubscriptionStatus
import org.devvikram.societymanagement.user.repository.UserRepository
import org.devvikram.societymanagement.user.repository.UserRoleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionalEventListener
import java.math.BigDecimal
import java.time.LocalDate

data class InvoiceIssued(
    val invoiceId: Long,
    val societyId: Long,
    val number: String,
    val total: BigDecimal,
    val dueOn: LocalDate,
    val billingContactId: Long?,
)

data class PaymentReceived(val invoiceId: Long, val societyId: Long, val number: String, val receipt: String, val amount: BigDecimal)

// Who hears about the society's billing: its billing contact, or else everyone who may see the
// subscription (SECRETARY and above).
@Component
class BillingAudience(
    private val userRoleRepository: UserRoleRepository,
    private val accessService: AccessService,
) {
    @Transactional(readOnly = true)
    fun of(societyId: Long, billingContactId: Long?): Set<Long> {
        if (billingContactId != null) return setOf(billingContactId)
        val roles = userRoleRepository.findByUserIds(userRoleRepository.findUserIdsBySocietyIds(listOf(societyId)))
        return roles.groupBy { it.user.id!! }
            .filter { (userId, userRoles) ->
                accessService.accessFor(userId, userRoles.map { it.toGrant() })
                    .canSocietyWide(Permissions.SOCIETY_SUBSCRIPTION_VIEW, societyId)
            }
            .keys
    }
}

// After commit, best-effort: a failed push is logged, never surfaced.
@Component
class SubscriptionNotificationListener(
    private val audience: BillingAudience,
    private val subscriptionService: SubscriptionService,
    private val userRepository: UserRepository,
    private val pushNotifier: PushNotifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener
    fun onInvoice(event: InvoiceIssued) = safely(event.invoiceId) {
        send(
            audience.of(event.societyId, event.billingContactId),
            "Invoice ${event.number}",
            "₹${event.total.toPlainString()} due by ${event.dueOn}",
            mapOf("type" to "PLATFORM_INVOICE", "invoiceId" to event.invoiceId.toString()),
        )
    }

    @TransactionalEventListener
    fun onPayment(event: PaymentReceived) = safely(event.invoiceId) {
        val contact = subscriptionService.view(event.societyId)?.subscription?.billingContactId
        send(
            audience.of(event.societyId, contact),
            "Payment received",
            "₹${event.amount.toPlainString()} for ${event.number} (receipt ${event.receipt})",
            mapOf("type" to "PLATFORM_PAYMENT", "invoiceId" to event.invoiceId.toString()),
        )
    }

    @TransactionalEventListener
    fun onReminder(event: InvoiceReminder) = safely(event.invoiceId) {
        val body = when {
            event.daysFromDue < 0 -> "₹${event.balance.toPlainString()} for ${event.number} is due on ${event.dueOn}"
            event.daysFromDue == 0L -> "₹${event.balance.toPlainString()} for ${event.number} is due today"
            else -> "₹${event.balance.toPlainString()} for ${event.number} was due on ${event.dueOn}; please pay to avoid suspension"
        }
        send(
            audience.of(event.societyId, event.billingContactId),
            if (event.daysFromDue <= 0) "Invoice due" else "Invoice overdue",
            body,
            mapOf("type" to "PLATFORM_INVOICE_REMINDER", "invoiceId" to event.invoiceId.toString()),
        )
    }

    @TransactionalEventListener
    fun onStatus(event: SubscriptionStatusChanged) = safely(event.societyId) {
        val (title, body) = when (event.to) {
            SubscriptionStatus.PAST_DUE -> "Subscription payment overdue" to "An invoice is past its due date. Pay it to keep using paid features."
            SubscriptionStatus.SUSPENDED -> "Subscription suspended" to
                "Paid features (bills, vouchers, policies, meetings) are read-only until the overdue invoice is paid. Visitors and complaints keep working."
            SubscriptionStatus.ACTIVE -> "Subscription active" to "Thank you — everything is available again."
            else -> return@safely
        }
        send(
            audience.of(event.societyId, event.billingContactId),
            title,
            body,
            mapOf("type" to "SUBSCRIPTION_STATUS", "status" to event.to.name),
        )
    }

    private fun send(userIds: Collection<Long>, title: String, body: String, data: Map<String, String>) {
        if (userIds.isEmpty()) return
        val tokens = userRepository.findAllById(userIds).filter { it.isActive }.mapNotNull { it.fcmToken }.distinct()
        if (tokens.isNotEmpty()) pushNotifier.send(tokens, title = title, body = body, data = data)
    }

    private fun safely(invoiceId: Long, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn("Billing push failed for invoice {}", invoiceId, e)
        }
    }
}
