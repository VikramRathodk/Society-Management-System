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

// AGM and SGM are general body meetings (every member flat is invited); COMMITTEE is the managing committee.
enum class MeetingKind {
    AGM, SGM, COMMITTEE;

    val general: Boolean get() = this != COMMITTEE
}

// DRAFT → NOTICE_SENT → HELD → MINUTES_PUBLISHED (from later steps); CANCELLED or ADJOURNED before it is held.
enum class MeetingStatus { DRAFT, NOTICE_SENT, CANCELLED, ADJOURNED, HELD, MINUTES_PUBLISHED }

@Entity
@Table(name = "meeting")
class Meeting(
    @Column(name = "society_id", nullable = false)
    var societyId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var kind: MeetingKind,

    @Column(nullable = false)
    var title: String,

    var description: String?,

    @Column(name = "scheduled_at", nullable = false)
    var scheduledAt: Instant,

    var place: String?,

    @Column(name = "online_link")
    var onlineLink: String?,

    @Column(name = "created_by", nullable = false)
    var createdBy: Long,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: MeetingStatus = MeetingStatus.DRAFT

    @Column(name = "notice_sent_at")
    var noticeSentAt: Instant? = null

    @Column(name = "notice_sent_by")
    var noticeSentBy: Long? = null

    @Column(name = "short_notice_reason")
    var shortNoticeReason: String? = null

    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null

    @Column(name = "cancelled_by")
    var cancelledBy: Long? = null

    @Column(name = "cancel_reason")
    var cancelReason: String? = null

    @Column(name = "adjourned_from_id")
    var adjournedFromId: Long? = null

    @Column(name = "adjourned_to_id")
    var adjournedToId: Long? = null

    @Column(name = "adjourn_reason")
    var adjournReason: String? = null

    @Column(name = "reminder_sent_at")
    var reminderSentAt: Instant? = null

    @Column(name = "updated_at")
    var updatedAt: Instant? = null

    @Column(name = "updated_by")
    var updatedBy: Long? = null
}

@Entity
@Table(name = "agenda_item")
class AgendaItem(
    @Column(name = "meeting_id", nullable = false)
    var meetingId: Long,

    @Column(nullable = false)
    var position: Int,

    @Column(nullable = false)
    var title: String,

    var description: String?,

    // A proposed resolution, voted on from step G2.
    @Column(name = "resolution_text")
    var resolutionText: String?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

enum class RsvpResponse { YES, NO, MAYBE }

// Whether an invitee says they'll come: a headcount guide, not attendance.
@Entity
@Table(name = "meeting_rsvp")
class MeetingRsvp(
    @Column(name = "meeting_id", nullable = false)
    var meetingId: Long,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var response: RsvpResponse,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

