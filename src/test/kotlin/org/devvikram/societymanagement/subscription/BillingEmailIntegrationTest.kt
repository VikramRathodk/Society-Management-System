package org.devvikram.societymanagement.subscription

import org.devvikram.societymanagement.notification.MailMessage
import org.devvikram.societymanagement.notification.MailResult
import org.devvikram.societymanagement.notification.Mailer
import org.devvikram.societymanagement.society.service.SocietyService
import org.devvikram.societymanagement.subscription.model.EmailKind
import org.devvikram.societymanagement.subscription.model.EmailStatus
import org.devvikram.societymanagement.subscription.model.SubscriptionStatus
import org.devvikram.societymanagement.subscription.repository.EmailLogRepository
import org.devvikram.societymanagement.subscription.service.BillingEmails
import org.devvikram.societymanagement.subscription.service.InvoiceIssued
import org.devvikram.societymanagement.subscription.service.InvoiceReminder
import org.devvikram.societymanagement.subscription.service.SubscriptionStatusChanged
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.repository.UserRepository
import org.devvikram.societymanagement.user.service.AssignRoleCommand
import org.devvikram.societymanagement.user.service.CreateUserCommand
import org.devvikram.societymanagement.user.service.UserService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestConfiguration
class RecordingMailerConfig {
    @Bean
    @Primary
    fun recordingMailer() = RecordingMailer()
}

class RecordingMailer : Mailer {
    val sent = mutableListOf<MailMessage>()
    var failWith: String? = null

    override fun send(message: MailMessage): MailResult {
        failWith?.let { return MailResult(false, it) }
        sent += message
        return MailResult(true)
    }
}

// Billing emails rendered, sent and logged. The listeners run after commit in the app; tests roll back, so
// they are called directly here. The log is written in its own transaction, so rows are removed afterwards.
@SpringBootTest(properties = ["app.billing.enabled=true"])
@Transactional
@Import(RecordingMailerConfig::class)
class BillingEmailIntegrationTest {

    @Autowired lateinit var userService: UserService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var societyService: SocietyService
    @Autowired lateinit var emails: BillingEmails
    @Autowired lateinit var mailer: RecordingMailer
    @Autowired lateinit var emailLogRepository: EmailLogRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var transactionManager: PlatformTransactionManager

    private var societyId = 0L
    private lateinit var contact: User
    private lateinit var secretary: User

    private fun user(role: String, email: String?): User {
        val u = userService.createUser(
            CreateUserCommand(
                name = "$role person", phone = "9${(100000000..999999999).random()}", rawPassword = "secret123",
                role = AssignRoleCommand(roleCode = role, societyId = societyId),
            )
        )
        u.email = email
        return userRepository.save(u)
    }

    @BeforeEach
    fun setUp() {
        mailer.sent.clear()
        mailer.failWith = null
        societyId = societyService.createSociety("Email Test Society").id!!
        contact = user("SECRETARY", "treasurer@example.com")
        secretary = user("SECRETARY", null)
    }

    @AfterEach
    fun cleanUp() {
        TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
            .executeWithoutResult { jdbc.update("DELETE FROM email_log WHERE society_id = ?", societyId) }
    }

    private fun invoiceIssued(contactId: Long?) =
        InvoiceIssued(42, societyId, "SAH/2026-27/0042", BigDecimal("1180.00"), LocalDate.of(2026, 12, 2), contactId)

    @Test
    fun `an invoice mail goes to the billing contact with the amount, due date and a link`() {
        emails.onInvoice(invoiceIssued(contact.id))
        val mail = mailer.sent.single()
        assertEquals("treasurer@example.com", mail.to)
        assertContains(mail.subject, "SAH/2026-27/0042")
        assertContains(mail.text, "₹1,180.00")
        assertContains(mail.text, "2 Dec 2026")
        assertContains(mail.text, "/subscription?invoice=42")
        assertContains(mail.html!!, "Email Test Society")
        val log = emailLogRepository.findBySociety(societyId).single()
        assertEquals(EmailStatus.SENT, log.status)
        assertEquals(EmailKind.INVOICE, log.kind)
    }

    @Test
    fun `without a billing contact the committee gets it, and someone without an email is logged as skipped`() {
        emails.onInvoice(invoiceIssued(null))
        // One secretary has an email (mailed); the other has none (skipped, push only).
        assertEquals(listOf("treasurer@example.com"), mailer.sent.map { it.to })
        val statuses = emailLogRepository.findBySociety(societyId).associate { it.userId to it.status }
        assertEquals(EmailStatus.SKIPPED, statuses[secretary.id])
        assertEquals(EmailStatus.SENT, statuses[contact.id])
    }

    @Test
    fun `an SMTP failure is logged and never thrown`() {
        mailer.failWith = "Connection refused"
        emails.onReminder(InvoiceReminder(42, societyId, "SAH/2026-27/0042", BigDecimal("1180.00"), LocalDate.of(2026, 12, 2), 1, contact.id))
        val log = emailLogRepository.findBySociety(societyId).single()
        assertEquals(EmailStatus.FAILED, log.status)
        assertEquals("Connection refused", log.error)
        assertEquals(EmailKind.REMINDER, log.kind)
    }

    @Test
    fun `suspension is mailed and an overdue reminder says so`() {
        emails.onStatus(SubscriptionStatusChanged(societyId, SubscriptionStatus.PAST_DUE, SubscriptionStatus.SUSPENDED, contact.id))
        assertContains(mailer.sent.single().subject, "suspended")
        assertTrue(mailer.sent.single().text.contains("Visitors, complaints"))
        emails.onReminder(InvoiceReminder(42, societyId, "SAH/2026-27/0042", BigDecimal("1180.00"), LocalDate.of(2026, 12, 2), 7, contact.id))
        assertContains(mailer.sent.last().subject, "Overdue")
        // PAST_DUE itself is a push only.
        emails.onStatus(SubscriptionStatusChanged(societyId, SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE, contact.id))
        assertEquals(2, mailer.sent.size)
    }
}
