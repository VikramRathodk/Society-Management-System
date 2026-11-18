package org.devvikram.societymanagement.subscription.service

import org.devvikram.societymanagement.subscription.model.SubscriptionEvent
import org.devvikram.societymanagement.subscription.model.SubscriptionEventKind
import org.devvikram.societymanagement.subscription.model.SubscriptionStatus
import org.devvikram.societymanagement.subscription.repository.InvoiceRepository
import org.devvikram.societymanagement.subscription.repository.SubscriptionEventRepository
import org.devvikram.societymanagement.subscription.repository.SubscriptionRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.context.annotation.Lazy
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class BillingRunResult(val invoiced: Int, val reminded: Int, val pastDue: Int, val suspended: Int)

data class InvoiceReminder(val invoiceId: Long, val societyId: Long, val number: String, val balance: java.math.BigDecimal, val dueOn: LocalDate, val daysFromDue: Long, val billingContactId: Long?)

data class SubscriptionStatusChanged(val societyId: Long, val from: SubscriptionStatus, val to: SubscriptionStatus, val billingContactId: Long?)

// One pass over every subscription for a day: issue invoices that are due, remind about unpaid ones on the
// chosen days, and move overdue societies to PAST_DUE and then SUSPENDED after the grace period. Paying
// makes a society ACTIVE again at once (InvoiceService). Safe to run more than once a day.
@Service
class BillingRunService(
    private val subscriptionRepository: SubscriptionRepository,
    private val invoiceRepository: InvoiceRepository,
    private val eventRepository: SubscriptionEventRepository,
    private val invoiceService: InvoiceService,
    private val events: ApplicationEventPublisher,
    @Value("\${app.billing.grace-days:15}") private val graceDays: Long,
    // Days relative to the due date: negative before, positive after.
    @Value("\${app.billing.reminder-days:-3,1,7}") reminderDays: String,
    // Ourselves through the proxy, so each society's step runs in its own transaction.
    @Lazy private val self: BillingRunService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val reminders: Set<Long> = reminderDays.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    fun run(today: LocalDate): BillingRunResult {
        var invoiced = 0
        var reminded = 0
        var pastDue = 0
        var suspended = 0
        for (subscription in subscriptionRepository.findAll().filter { it.status != SubscriptionStatus.CANCELLED }) {
            // Each society on its own: one failing never stops the others.
            try {
                val r = self.runOne(subscription.societyId, today)
                invoiced += r.invoiced
                reminded += r.reminded
                pastDue += r.pastDue
                suspended += r.suspended
            } catch (e: Exception) {
                log.warn("Billing run failed for society {}", subscription.societyId, e)
            }
        }
        return BillingRunResult(invoiced, reminded, pastDue, suspended)
    }

    @Transactional
    fun runOne(societyId: Long, today: LocalDate): BillingRunResult {
        var invoiced = 0
        // Catch up: a society several periods behind (the run was off) gets each period's invoice.
        while (true) {
            invoiceService.issueDue(societyId, null, today) ?: break
            invoiced++
            if (invoiced > 24) break
        }
        val subscription = subscriptionRepository.lockBySociety(societyId) ?: return BillingRunResult(invoiced, 0, 0, 0)
        var reminded = 0
        val unpaid = invoiceRepository.findBySociety(societyId).filter { it.balance.signum() > 0 }
        for (invoice in unpaid) {
            val days = ChronoUnit.DAYS.between(invoice.dueOn, today)
            if (days in reminders && invoice.lastRemindedOn != today) {
                invoice.lastRemindedOn = today
                invoiceRepository.save(invoice)
                events.publishEvent(
                    InvoiceReminder(invoice.id!!, societyId, invoice.number, invoice.balance, invoice.dueOn, days, subscription.billingContactId)
                )
                reminded++
            }
        }
        val oldestOverdue = unpaid.filter { it.dueOn.isBefore(today) }.minOfOrNull { it.dueOn }
        val target = when {
            oldestOverdue == null -> null
            oldestOverdue.plusDays(graceDays).isBefore(today) -> SubscriptionStatus.SUSPENDED
            else -> SubscriptionStatus.PAST_DUE
        }
        var pastDue = 0
        var suspended = 0
        if (target != null && subscription.status != target &&
            (subscription.status == SubscriptionStatus.ACTIVE || (subscription.status == SubscriptionStatus.PAST_DUE && target == SubscriptionStatus.SUSPENDED))
        ) {
            val from = subscription.status
            subscription.status = target
            subscriptionRepository.save(subscription)
            eventRepository.save(
                SubscriptionEvent(subscription.id!!, SubscriptionEventKind.STATUS_CHANGED, from.name, target.name, "Invoice overdue since $oldestOverdue", null)
            )
            events.publishEvent(SubscriptionStatusChanged(societyId, from, target, subscription.billingContactId))
            if (target == SubscriptionStatus.SUSPENDED) suspended++ else pastDue++
        }
        return BillingRunResult(invoiced, reminded, pastDue, suspended)
    }
}

// Every morning (`app.billing.run-cron`, default 06:00 in the report zone), when billing is switched on.
@Component
class BillingRunJob(
    private val billingRun: BillingRunService,
    private val subscriptionService: SubscriptionService,
    @Value("\${app.billing.enabled:false}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${app.billing.run-cron:0 0 6 * * *}", zone = "\${app.report.zone}")
    fun run() {
        if (!enabled) return
        val result = billingRun.run(subscriptionService.today())
        log.info("Billing run: {}", result)
    }
}
