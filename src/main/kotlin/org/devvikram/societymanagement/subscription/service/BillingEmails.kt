package org.devvikram.societymanagement.subscription.service

import org.devvikram.societymanagement.notification.MailMessage
import org.devvikram.societymanagement.notification.Mailer
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.subscription.model.EmailKind
import org.devvikram.societymanagement.subscription.model.EmailLog
import org.devvikram.societymanagement.subscription.model.EmailStatus
import org.devvikram.societymanagement.subscription.model.SubscriptionStatus
import org.devvikram.societymanagement.subscription.repository.EmailLogRepository
import org.devvikram.societymanagement.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionalEventListener
import java.math.BigDecimal
import java.time.LocalDate

// Writes the email log in its own transaction: mails go out after the billing change committed.
@Component
class EmailLogWriter(private val repository: EmailLogRepository) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun write(log: EmailLog) {
        repository.save(log)
    }
}

// Billing emails to the billing contact (or else SECRETARY and above), next to the pushes: invoices
// (with a link to view and pay), receipts, reminders and suspension. Someone without an email address is
// logged as SKIPPED (they still got the push). Best-effort: a failed mail is logged, never blocks billing.
@Component
class BillingEmails(
    private val audience: BillingAudience,
    private val subscriptionService: SubscriptionService,
    private val societyService: SocietyService,
    private val userRepository: UserRepository,
    private val mailer: Mailer,
    private val logWriter: EmailLogWriter,
    private val invoiceService: InvoiceService,
    @Value("\${app.web-base-url}") private val webBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener
    fun onInvoice(event: InvoiceIssued) = safely {
        val link = invoiceLink(event.invoiceId)
        send(
            event.societyId, event.billingContactId, EmailKind.INVOICE, event.invoiceId,
            "Invoice ${event.number}: ${rupees(event.total)} due by ${date(event.dueOn)}",
            listOf(
                "Your ${platformName()} invoice ${event.number} for ${societyName(event.societyId)} is ready.",
                "Amount: ${rupees(event.total)} (GST included where it applies)",
                "Due by: ${date(event.dueOn)}",
            ),
            "View and pay the invoice" to link,
        )
    }

    @TransactionalEventListener
    fun onPayment(event: PaymentReceived) = safely {
        val contact = subscriptionService.view(event.societyId)?.subscription?.billingContactId
        send(
            event.societyId, contact, EmailKind.RECEIPT, event.invoiceId,
            "Payment received for ${event.number} (receipt ${event.receipt})",
            listOf(
                "Thank you. We received ${rupees(event.amount)} from ${societyName(event.societyId)} for invoice ${event.number}.",
                "Receipt: ${event.receipt}",
            ),
            "View the invoice and receipt" to invoiceLink(event.invoiceId),
        )
    }

    @TransactionalEventListener
    fun onReminder(event: InvoiceReminder) = safely {
        val (subject, line) = when {
            event.daysFromDue < 0 -> "Invoice ${event.number} is due on ${date(event.dueOn)}" to
                "A reminder that ${rupees(event.balance)} for invoice ${event.number} is due on ${date(event.dueOn)}."
            event.daysFromDue == 0L -> "Invoice ${event.number} is due today" to "${rupees(event.balance)} for invoice ${event.number} is due today."
            else -> "Overdue: invoice ${event.number}" to
                "${rupees(event.balance)} for invoice ${event.number} was due on ${date(event.dueOn)}. Please pay to avoid the suspension of paid features."
        }
        send(event.societyId, event.billingContactId, EmailKind.REMINDER, event.invoiceId, subject, listOf(line), "Pay now" to invoiceLink(event.invoiceId))
    }

    @TransactionalEventListener
    fun onStatus(event: SubscriptionStatusChanged) = safely {
        val (subject, lines) = when (event.to) {
            SubscriptionStatus.SUSPENDED -> "${platformName()} subscription suspended for ${societyName(event.societyId)}" to listOf(
                "An invoice is still unpaid after the grace period, so bills, vouchers, policies and meetings are read-only for now.",
                "Visitors, complaints, notices and residents' sign-in keep working.",
                "Paying the overdue invoice restores everything at once.",
            )
            SubscriptionStatus.ACTIVE -> if (event.from == SubscriptionStatus.SUSPENDED) {
                "${platformName()} subscription active again" to listOf("Thank you for your payment. Everything is available again.")
            } else {
                return@safely
            }
            else -> return@safely
        }
        send(event.societyId, event.billingContactId, EmailKind.STATUS, null, subject, lines, "Open the subscription" to "$webBaseUrl/subscription")
    }

    private fun send(
        societyId: Long,
        billingContactId: Long?,
        kind: EmailKind,
        relatedId: Long?,
        subject: String,
        lines: List<String>,
        action: Pair<String, String>,
    ) {
        val recipients = userRepository.findAllById(audience.of(societyId, billingContactId)).filter { it.isActive }
        for (user in recipients) {
            val to = user.email?.trim()?.ifEmpty { null }
            if (to == null) {
                logWriter.write(EmailLog(null, user.id, societyId, subject, kind, relatedId, EmailStatus.SKIPPED, "No email address"))
                continue
            }
            val result = mailer.send(MailMessage(to, subject, text(user.name, lines, action), html(user.name, lines, action)))
            logWriter.write(
                EmailLog(to, user.id, societyId, subject, kind, relatedId, if (result.sent) EmailStatus.SENT else EmailStatus.FAILED, result.error)
            )
        }
    }

    private fun text(name: String, lines: List<String>, action: Pair<String, String>) = buildString {
        appendLine("Hello $name,")
        appendLine()
        lines.forEach { appendLine(it) }
        appendLine()
        appendLine("${action.first}: ${action.second}")
        appendLine()
        append("— ${platformName()}")
    }

    private fun html(name: String, lines: List<String>, action: Pair<String, String>) = buildString {
        append("<div style=\"font-family:Arial,sans-serif;font-size:14px;color:#1f2937;line-height:1.5\">")
        append("<p>Hello ${esc(name)},</p>")
        lines.forEach { append("<p>${esc(it)}</p>") }
        append("<p><a href=\"${esc(action.second)}\" style=\"display:inline-block;padding:10px 16px;background:#2563eb;color:#fff;")
        append("text-decoration:none;border-radius:6px\">${esc(action.first)}</a></p>")
        append("<p style=\"color:#6b7280\">— ${esc(platformName())}</p></div>")
    }

    private fun invoiceLink(invoiceId: Long) = "$webBaseUrl/subscription?invoice=$invoiceId"

    private fun platformName() = invoiceService.platform.legalName

    private fun societyName(societyId: Long) = societyService.getById(societyId).name

    private fun rupees(amount: BigDecimal) = "₹" + "%,.2f".format(amount)

    private fun date(day: LocalDate) = "%d %s %d".format(day.dayOfMonth, day.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3), day.year)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn("Billing email failed", e)
        }
    }
}
