package org.devvikram.societymanagement.governance.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// DRAFT (the secretary writes) → PENDING (submitted) → PUBLISHED (approved); sent back to DRAFT with a reason.
enum class MinutesStatus { DRAFT, PENDING, PUBLISHED }

// The minutes of one meeting. Never changed once published; corrections are addenda.
@Entity
@Table(name = "meeting_minutes")
class MeetingMinutes(
    @Column(name = "meeting_id", nullable = false)
    var meetingId: Long,

    @Column(nullable = false)
    var body: String,

    @Column(name = "drafted_by", nullable = false)
    var draftedBy: Long,

    @Column(name = "drafted_at", nullable = false)
    var draftedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: MinutesStatus = MinutesStatus.DRAFT

    @Column(name = "attachment_key")
    var attachmentKey: String? = null

    @Column(name = "attachment_name")
    var attachmentName: String? = null

    @Column(name = "attachment_type")
    var attachmentType: String? = null

    @Column(name = "attachment_size")
    var attachmentSize: Int? = null

    @Column(name = "updated_at")
    var updatedAt: Instant? = null

    @Column(name = "submitted_at")
    var submittedAt: Instant? = null

    @Column(name = "reviewed_by")
    var reviewedBy: Long? = null

    @Column(name = "reviewed_at")
    var reviewedAt: Instant? = null

    @Column(name = "review_reason")
    var reviewReason: String? = null

    @Column(name = "approved_by")
    var approvedBy: Long? = null

    @Column(name = "published_at")
    var publishedAt: Instant? = null

    // The notice-board post announcing the published minutes, if any.
    @Column(name = "announced_notice_id")
    var announcedNoticeId: Long? = null
}

// A dated correction to published minutes.
@Entity
@Table(name = "minutes_addendum")
class MinutesAddendum(
    @Column(name = "minutes_id", nullable = false)
    var minutesId: Long,

    @Column(nullable = false)
    var text: String,

    @Column(name = "created_by", nullable = false)
    var createdBy: Long,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
