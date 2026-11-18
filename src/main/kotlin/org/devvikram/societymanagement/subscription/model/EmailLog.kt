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

enum class EmailKind { INVOICE, RECEIPT, REMINDER, STATUS }

// SKIPPED: the person has no email address (they got the push only).
enum class EmailStatus { SENT, FAILED, SKIPPED }

// One billing email, sent or not.
@Entity
@Table(name = "email_log")
class EmailLog(
    @Column(name = "to_address") var toAddress: String?,
    @Column(name = "user_id") var userId: Long?,
    @Column(name = "society_id") var societyId: Long?,
    @Column(nullable = false) var subject: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var kind: EmailKind,
    @Column(name = "related_id") var relatedId: Long?,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: EmailStatus,
    var error: String?,
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
