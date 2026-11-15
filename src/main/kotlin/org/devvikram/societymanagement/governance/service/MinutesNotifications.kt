package org.devvikram.societymanagement.governance.service

import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.governance.model.MeetingKind
import org.devvikram.societymanagement.notification.PushNotifier
import org.devvikram.societymanagement.user.repository.UserRepository
import org.devvikram.societymanagement.user.repository.UserRoleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

data class MinutesSubmitted(val meetingId: Long, val societyId: Long, val title: String, val draftedBy: Long)

data class MinutesSentBack(val meetingId: Long, val title: String, val draftedBy: Long, val reason: String)

data class MinutesPublished(val meetingId: Long, val societyId: Long, val kind: MeetingKind, val title: String, val byUserId: Long)

// After commit, best-effort: a failed push is logged, never surfaced to whoever acted.
@Component
class MinutesNotificationListener(
    private val audience: MeetingAudience,
    private val userRoleRepository: UserRoleRepository,
    private val accessService: AccessService,
    private val userRepository: UserRepository,
    private val pushNotifier: PushNotifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // To everyone who may approve minutes in the society, except the drafter (who can't).
    @TransactionalEventListener
    fun onSubmitted(event: MinutesSubmitted) = safely("submitted", event.meetingId) {
        val roles = userRoleRepository.findByUserIds(userRoleRepository.findUserIdsBySocietyIds(listOf(event.societyId)))
        val approvers = roles.groupBy { it.user.id!! }
            .filter { (userId, userRoles) ->
                userId != event.draftedBy && accessService.accessFor(userId, userRoles.map { it.toGrant() })
                    .canSocietyWide(Permissions.GOVERNANCE_MINUTES_APPROVE, event.societyId)
            }
            .keys
        send(approvers, "Minutes to approve", event.title, "MINUTES_SUBMITTED", event.meetingId)
    }

    @TransactionalEventListener
    fun onSentBack(event: MinutesSentBack) = safely("sent back", event.meetingId) {
        send(listOf(event.draftedBy), "Minutes sent back", "${event.title}: ${event.reason}", "MINUTES_SENT_BACK", event.meetingId)
    }

    // To whoever may see the meeting: every member for general meetings, the committee for its own.
    @TransactionalEventListener
    fun onPublished(event: MinutesPublished) = safely("published", event.meetingId) {
        send(audience.invitees(event.societyId, event.kind) - event.byUserId, "Minutes published", event.title, "MINUTES_PUBLISHED", event.meetingId)
    }

    private fun send(userIds: Collection<Long>, title: String, body: String, type: String, meetingId: Long) {
        if (userIds.isEmpty()) return
        val tokens = userRepository.findAllById(userIds).filter { it.isActive }.mapNotNull { it.fcmToken }.distinct()
        if (tokens.isNotEmpty()) {
            pushNotifier.send(tokens, title = title, body = body, data = mapOf("type" to type, "meetingId" to meetingId.toString()))
        }
    }

    private fun safely(what: String, meetingId: Long, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn("Push notification failed for minutes of meeting {} {}", meetingId, what, e)
        }
    }
}
