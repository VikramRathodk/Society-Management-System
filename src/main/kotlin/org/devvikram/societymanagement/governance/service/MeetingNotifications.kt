package org.devvikram.societymanagement.governance.service

import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.governance.model.MeetingKind
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

// Who is invited to a meeting: for AGMs and SGMs everyone holding GOVERNANCE.MEETING.VIEW in the
// society (residents and the committee, not guards); for committee meetings those holding
// GOVERNANCE.COMMITTEE.VIEW through a society-wide role.
@Component
class MeetingAudience(
    private val userRoleRepository: UserRoleRepository,
    private val accessService: AccessService,
) {
    fun invitees(societyId: Long, kind: MeetingKind): Set<Long> {
        val roles = userRoleRepository.findByUserIds(userRoleRepository.findUserIdsBySocietyIds(listOf(societyId)))
        return roles.groupBy { it.user.id!! }
            .filter { (userId, userRoles) ->
                val access = accessService.accessFor(userId, userRoles.map { it.toGrant() })
                if (kind.general) {
                    access.can(Permissions.GOVERNANCE_MEETING_VIEW, societyId)
                } else {
                    access.canSocietyWide(Permissions.GOVERNANCE_COMMITTEE_VIEW, societyId)
                }
            }
            .keys
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
