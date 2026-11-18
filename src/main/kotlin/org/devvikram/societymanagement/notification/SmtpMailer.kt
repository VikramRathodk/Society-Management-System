package org.devvikram.societymanagement.notification

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component

// Delivers through any SMTP server (Amazon SES, SendGrid, Zoho, Gmail … through their SMTP endpoints):
// spring.mail.* from SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD; the sender is MAIL_FROM.
@Component
@ConditionalOnProperty(name = ["app.mail.provider"], havingValue = "smtp")
class SmtpMailer(
    private val sender: JavaMailSender,
    @Value("\${app.mail.from}") private val from: String,
) : Mailer {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(from.isNotBlank()) { "app.mail.provider=smtp needs MAIL_FROM" }
    }

    override fun send(message: MailMessage): MailResult = try {
        val mime = sender.createMimeMessage()
        MimeMessageHelper(mime, false, "UTF-8").apply {
            setFrom(from)
            setTo(message.to)
            setSubject(message.subject)
            if (message.html != null) setText(message.text, message.html) else setText(message.text)
        }
        sender.send(mime)
        MailResult(true)
    } catch (e: Exception) {
        log.warn("Mail to {} failed: {}", message.to, e.message)
        MailResult(false, (e.message ?: e.javaClass.simpleName).take(500))
    }
}
