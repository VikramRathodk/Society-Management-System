package org.devvikram.societymanagement.notification

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

data class MailMessage(
    val to: String,
    val subject: String,
    // Plain text, always; HTML when given (mail clients show it instead).
    val text: String,
    val html: String? = null,
)

// What happened to one mail: sent, or why not.
data class MailResult(val sent: Boolean, val error: String? = null)

// Sends an email. Like push, best-effort: never throws; a failure is answered (and logged), never undoes
// what triggered the mail.
interface Mailer {
    fun send(message: MailMessage): MailResult
}

// Default (app.mail.provider=log or unset): logs what would be sent, so dev runs and tests need no SMTP
// account. app.mail.provider=smtp swaps in SmtpMailer; callers don't change.
@Component
@ConditionalOnProperty(name = ["app.mail.provider"], havingValue = "log", matchIfMissing = true)
class LoggingMailer : Mailer {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(message: MailMessage): MailResult {
        log.info("[mail] to {}: {}\n{}", message.to, message.subject, message.text)
        return MailResult(true)
    }
}
