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

    @Column(name = "held_at")
    var heldAt: Instant? = null

    @Column(name = "held_by")
    var heldBy: Long? = null

    @Column(name = "chairperson_id")
    var chairpersonId: Long? = null

    // Frozen when the meeting is marked held: member flats (general) or committee members, the quorum
    // needed and how many of them were present.
    @Column(name = "member_count")
    var memberCount: Int? = null

    @Column(name = "quorum_required")
    var quorumRequired: Int? = null

    @Column(name = "quorum_present")
    var quorumPresent: Int? = null

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

    @Column(name = "proposed_by")
    var proposedBy: Long? = null

    @Column(name = "seconded_by")
    var secondedBy: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "voting_status", nullable = false)
    var votingStatus: VotingStatus = VotingStatus.NOT_OPEN

    @Column(name = "voting_opened_at")
    var votingOpenedAt: Instant? = null

    @Column(name = "voting_opened_by")
    var votingOpenedBy: Long? = null

    @Column(name = "voting_closed_at")
    var votingClosedAt: Instant? = null

    @Column(name = "voting_closed_by")
    var votingClosedBy: Long? = null

    // Counts only: which way a voter went is never stored.
    @Column(name = "votes_for", nullable = false)
    var votesFor: Int = 0

    @Column(name = "votes_against", nullable = false)
    var votesAgainst: Int = 0

    @Column(name = "votes_abstain", nullable = false)
    var votesAbstain: Int = 0

    @Enumerated(EnumType.STRING)
    var outcome: Outcome? = null

    @Column(name = "resolution_number")
    var resolutionNumber: String? = null

    // A policy the resolution refers to ("adopts version 3 of the Parking rules"): a reference only.
    @Column(name = "policy_id")
    var policyId: Long? = null
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

enum class VotingStatus { NOT_OPEN, OPEN, CLOSED }

// PASSED when more voted for than against (abstentions don't count); DEFERRED without a vote.
enum class Outcome { PASSED, REJECTED, DEFERRED }

enum class VoteChoice { FOR, AGAINST, ABSTAIN }

// That a voter (a member flat at general meetings, a committee member at committee meetings) voted on an
// item — not how.
@Entity
@Table(name = "ballot")
class Ballot(
    @Column(name = "agenda_item_id", nullable = false)
    var agendaItemId: Long,

    // "F<flat id>" or "U<user id>".
    @Column(name = "voter_key", nullable = false)
    var voterKey: String,

    @Column(name = "flat_id")
    var flatId: Long?,

    @Column(name = "voted_by", nullable = false)
    var votedBy: Long,

    @Column(name = "voted_at", nullable = false)
    var votedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

// Someone present at a meeting.
@Entity
@Table(name = "meeting_attendance")
class Attendance(
    @Column(name = "meeting_id", nullable = false)
    var meetingId: Long,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "recorded_by", nullable = false)
    var recordedBy: Long,

    @Column(name = "recorded_at", nullable = false)
    var recordedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
