package org.devvikram.societymanagement.policy.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.temporal.ChronoUnit

// "I have read and accept", by one person, against the version that asked for it: the latest published
// version marked as a material change. Later minor versions keep counting it.
@Entity
@Table(name = "policy_acknowledgement")
class PolicyAcknowledgement(
    @Column(name = "version_id", nullable = false)
    var versionId: Long,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    // The flat they acknowledged as (null if the flat was deleted since).
    @Column(name = "flat_id")
    var flatId: Long?,

    // To the microsecond, as Postgres keeps it, so the first answer matches every later read.
    @Column(name = "acknowledged_at", nullable = false)
    var acknowledgedAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

// A reminder push sent to a flat that hadn't acknowledged, so it isn't reminded again too soon.
@Entity
@Table(name = "policy_reminder")
class PolicyReminder(
    @Column(name = "version_id", nullable = false)
    var versionId: Long,

    @Column(name = "flat_id", nullable = false)
    var flatId: Long,

    @Column(name = "sent_by", nullable = false)
    var sentBy: Long,

    @Column(name = "sent_at", nullable = false)
    var sentAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
