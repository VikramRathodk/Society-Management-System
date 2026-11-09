package org.devvikram.societymanagement.invitation.service

import org.devvikram.societymanagement.notification.PushNotifier
import org.devvikram.societymanagement.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

// Events carry everything the message needs, so listeners never touch lazy associations.
data class InvitationSent(
    val invitationId: Long,
    val phone: String,
    val inviterName: String,
    val roleName: String,
    val societyName: String,
)

data class InvitationAccepted(
    val invitationId: Long,
    val inviterId: Long,
    val personName: String,
    val roleName: String,
    val societyName: String,
)

// After commit, best-effort: a failure is logged, never surfaced to the person who acted. Someone without
// an account yet gets the invitation only through the link the inviter shares.
@Component
class InvitationNotificationListener(
    private val userRepository: UserRepository,
    private val pushNotifier: PushNotifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener
    fun onSent(event: InvitationSent) {
        try {
            val token = userRepository.findByPhone(event.phone)?.takeIf { it.isActive }?.fcmToken ?: return
            pushNotifier.send(
                listOf(token),
                title = "You're invited to ${event.societyName}",
                body = "${event.inviterName} invited you to join as ${event.roleName}",
                data = mapOf("type" to "INVITATION_RECEIVED", "invitationId" to event.invitationId.toString()),
            )
        } catch (e: Exception) {
            log.warn("Push notification failed for invitation {}", event.invitationId, e)
        }
    }

    @TransactionalEventListener
    fun onAccepted(event: InvitationAccepted) {
        try {
            val token = userRepository.findById(event.inviterId)?.takeIf { it.isActive }?.fcmToken ?: return
            pushNotifier.send(
                listOf(token),
                title = "Invitation accepted",
                body = "${event.personName} joined ${event.societyName} as ${event.roleName}",
                data = mapOf("type" to "INVITATION_ACCEPTED", "invitationId" to event.invitationId.toString()),
            )
        } catch (e: Exception) {
            log.warn("Push notification failed for accepted invitation {}", event.invitationId, e)
        }
    }
}
