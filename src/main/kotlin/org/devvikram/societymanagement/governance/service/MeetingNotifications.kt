package org.devvikram.societymanagement.governance.service

import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.governance.model.MeetingKind
import org.devvikram.societymanagement.governance.model.Outcome
import org.devvikram.societymanagement.notification.PushNotifier
import org.devvikram.societymanagement.user.repository.UserRepository
import org.devvikram.societymanagement.user.repository.UserRoleRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Events carry everything the message needs, so listeners never read the meeting again.
data class MeetingNoticeSent(
    val meetingId: Long,
    val societyId: Long,
    val kind: MeetingKind,
    val title: String,
    val scheduledAt: Instant,
    val byUserId: Long,
)

data class MeetingCancelled(
    val meetingId: Long,
    val societyId: Long,
    val kind: MeetingKind,
    val title: String,
    val scheduledAt: Instant,
    val reason: String,
    val byUserId: Long,
)

data class MeetingAdjourned(
    val meetingId: Long,
    val newMeetingId: Long,
    val societyId: Long,
    val kind: MeetingKind,
    val title: String,
    val from: Instant,
    val to: Instant,
    val reason: String,
    val byUserId: Long,
)

data class MeetingReminder(
    val meetingId: Long,
    val societyId: Long,
    val kind: MeetingKind,
    val title: String,
    val scheduledAt: Instant,
)

data class VotingOpened(
    val meetingId: Long,
    val societyId: Long,
    val kind: MeetingKind,
    val meetingTitle: String,
    val itemTitle: String,
    val byUserId: Long,
)

data class VotingClosed(
    val meetingId: Long,
    val societyId: Long,
    val kind: MeetingKind,
    val itemTitle: String,
    val outcome: Outcome,
    val votesFor: Int,
    val votesAgainst: Int,
    val votesAbstain: Int,
)

// Who is invited to a meeting: for AGMs and SGMs everyone holding GOVERNANCE.MEETING.VIEW in the
// society (residents and the committee, not guards); for committee meetings those holding
// GOVERNANCE.COMMITTEE.VIEW through a society-wide role.
@Component
class MeetingAudience(
    private val userRoleRepository: UserRoleRepository,
    private val accessService: AccessService,
) {
    fun invitees(societyId: Long, kind: MeetingKind): Set<Long> =
        people(societyId).filter { if (kind.general) it.seesGeneral else it.committee }.map { it.userId }.toSet()

    // Member flats and who may vote for each: active holders of GOVERNANCE.VOTE.CREATE through a flat role
    // on the owner side (owner, family, or no type; not tenants).
    fun memberFlats(societyId: Long): Map<Long, Set<Long>> =
        people(societyId).filter { it.active }
            .flatMap { p -> p.voterFlats.map { it to p.userId } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, users) -> users.toSet() }

    // Active committee members: holders of GOVERNANCE.COMMITTEE.VIEW through a society-wide role.
    fun committeeMembers(societyId: Long): Set<Long> =
        people(societyId).filter { it.active && it.committee }.map { it.userId }.toSet()

    data class Person(
        val userId: Long,
        val name: String,
        val active: Boolean,
        val seesGeneral: Boolean,
        val committee: Boolean,
        val voterFlats: Set<Long>,
    )

    fun people(societyId: Long): List<Person> {
        val roles = userRoleRepository.findByUserIds(userRoleRepository.findUserIdsBySocietyIds(listOf(societyId)))
        return roles.groupBy { it.user.id!! }.map { (userId, userRoles) ->
            val grants = userRoles.map { it.toGrant() }
            val access = accessService.accessFor(userId, grants)
            Person(
                userId = userId,
                name = userRoles.first().user.name,
                active = userRoles.first().user.isActive,
                seesGeneral = access.can(Permissions.GOVERNANCE_MEETING_VIEW, societyId),
                committee = access.canSocietyWide(Permissions.GOVERNANCE_COMMITTEE_VIEW, societyId),
                voterFlats = voterFlats(access, societyId),
            )
        }
    }

    companion object {
        // Flats of the society the user votes for: flat roles holding VOTE.CREATE, owner side.
        fun voterFlats(access: Access, societyId: Long): Set<Long> {
            val allowed = access.flatsWith(Permissions.GOVERNANCE_VOTE_CREATE)
            return access.grants
                .filter { it.societyId == societyId && it.flatId != null && it.flatId in allowed && it.residentType != "TENANT" }
                .mapNotNull { it.flatId }
                .toSet()
        }
    }
}

// After commit, best-effort: a failed push is logged, never surfaced to whoever acted.
@Component
class MeetingNotificationListener(
    private val audience: MeetingAudience,
    private val userRepository: UserRepository,
    private val pushNotifier: PushNotifier,
    @Value("\${app.report.zone}") private val zone: ZoneId,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val when_ = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")

    @TransactionalEventListener
    fun onNotice(event: MeetingNoticeSent) = safely("notice", event.meetingId) {
        send(
            audience.invitees(event.societyId, event.kind) - event.byUserId,
            title = "${label(event.kind)} called",
            body = "${event.title} — ${at(event.scheduledAt)}",
            data = mapOf("type" to "MEETING_NOTICE", "meetingId" to event.meetingId.toString()),
        )
    }

    @TransactionalEventListener
    fun onCancelled(event: MeetingCancelled) = safely("cancelled", event.meetingId) {
        send(
            audience.invitees(event.societyId, event.kind) - event.byUserId,
            title = "${label(event.kind)} cancelled",
            body = "${event.title} on ${at(event.scheduledAt)}: ${event.reason}",
            data = mapOf("type" to "MEETING_CANCELLED", "meetingId" to event.meetingId.toString()),
        )
    }

    @TransactionalEventListener
    fun onAdjourned(event: MeetingAdjourned) = safely("adjourned", event.meetingId) {
        send(
            audience.invitees(event.societyId, event.kind) - event.byUserId,
            title = "${label(event.kind)} adjourned",
            body = "${event.title} moves to ${at(event.to)}",
            data = mapOf("type" to "MEETING_ADJOURNED", "meetingId" to event.newMeetingId.toString()),
        )
    }

    @TransactionalEventListener
    fun onReminder(event: MeetingReminder) = safely("reminder", event.meetingId) {
        send(
            audience.invitees(event.societyId, event.kind),
            title = "${label(event.kind)} soon",
            body = "${event.title} — ${at(event.scheduledAt)}",
            data = mapOf("type" to "MEETING_REMINDER", "meetingId" to event.meetingId.toString()),
        )
    }

    // To those who may vote: the owner side of member flats, or committee members.
    @TransactionalEventListener
    fun onVotingOpened(event: VotingOpened) = safely("voting opened", event.meetingId) {
        val voters = if (event.kind.general) {
            audience.memberFlats(event.societyId).values.flatten().toSet()
        } else {
            audience.committeeMembers(event.societyId)
        }
        send(
            voters,
            title = "Vote now",
            body = "${event.itemTitle} — ${event.meetingTitle}",
            data = mapOf("type" to "VOTING_OPEN", "meetingId" to event.meetingId.toString()),
        )
    }

    @TransactionalEventListener
    fun onVotingClosed(event: VotingClosed) = safely("voting closed", event.meetingId) {
        val result = when (event.outcome) {
            Outcome.PASSED -> "passed"
            Outcome.REJECTED -> "not passed"
            Outcome.DEFERRED -> "deferred"
        }
        send(
            audience.invitees(event.societyId, event.kind),
            title = "Resolution $result",
            body = "${event.itemTitle}: ${event.votesFor} for, ${event.votesAgainst} against, ${event.votesAbstain} abstained",
            data = mapOf("type" to "VOTING_CLOSED", "meetingId" to event.meetingId.toString()),
        )
    }

    private fun label(kind: MeetingKind) = when (kind) {
        MeetingKind.AGM -> "Annual general meeting"
        MeetingKind.SGM -> "Special general meeting"
        MeetingKind.COMMITTEE -> "Committee meeting"
    }

    private fun at(instant: Instant) = when_.format(instant.atZone(zone))

    private fun send(userIds: Collection<Long>, title: String, body: String, data: Map<String, String>) {
        if (userIds.isEmpty()) return
        val tokens = userRepository.findAllById(userIds).filter { it.isActive }.mapNotNull { it.fcmToken }.distinct()
        if (tokens.isNotEmpty()) pushNotifier.send(tokens, title = title, body = body, data = data)
    }

    private fun safely(what: String, meetingId: Long, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn("Push notification failed for meeting {} {}", meetingId, what, e)
        }
    }
}
