package org.devvikram.societymanagement.invitation.service

import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.notification.PushNotifier
import org.devvikram.societymanagement.user.repository.UserRepository
import org.devvikram.societymanagement.user.repository.UserRoleRepository
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

// A resident's invitation is waiting for the committee.
data class InvitationNeedsApproval(
    val invitationId: Long,
    val societyId: Long,
    val inviterName: String,
    val personName: String,
    val flatLabel: String?,
)

// The committee approved or rejected a resident's invitation.
data class InvitationDecided(
    val invitationId: Long,
    val inviterId: Long,
    val personName: String,
    val societyName: String,
    val approved: Boolean,
)

// After commit, best-effort: a failure is logged, never surfaced to the person who acted. Someone without
// an account yet gets the invitation only through the link the inviter shares.
@Component
class InvitationNotificationListener(
    private val userRepository: UserRepository,
    private val userRoleRepository: UserRoleRepository,
    private val accessService: AccessService,
    private val pushNotifier: PushNotifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // To everyone who may approve invitations in that society (society-wide, so not residents).
    @TransactionalEventListener
    fun onNeedsApproval(event: InvitationNeedsApproval) {
        try {
            val roles = userRoleRepository.findByUserIds(userRoleRepository.findUserIdsBySocietyIds(listOf(event.societyId)))
            val approverIds = roles.groupBy { it.user.id!! }
                .filter { (userId, userRoles) ->
                    accessService.accessFor(userId, userRoles.map { it.toGrant() })
                        .canSocietyWide(Permissions.USER_INVITE_APPROVE, event.societyId)
                }
                .keys
            val tokens = userRepository.findAllById(approverIds).filter { it.isActive }.mapNotNull { it.fcmToken }.distinct()
            if (tokens.isEmpty()) return
            val flat = event.flatLabel?.let { " to flat $it" } ?: ""
            pushNotifier.send(
                tokens,
                title = "Invitation to approve",
                body = "${event.inviterName} invited ${event.personName}$flat",
                data = mapOf("type" to "INVITATION_NEEDS_APPROVAL", "invitationId" to event.invitationId.toString()),
            )
        } catch (e: Exception) {
            log.warn("Push notification failed for invitation {} awaiting approval", event.invitationId, e)
        }
    }

    @TransactionalEventListener
    fun onDecided(event: InvitationDecided) {
        try {
            val token = userRepository.findById(event.inviterId)?.takeIf { it.isActive }?.fcmToken ?: return
            pushNotifier.send(
                listOf(token),
                title = if (event.approved) "Invitation approved" else "Invitation not approved",
                body = if (event.approved) {
                    "Your invitation for ${event.personName} to ${event.societyName} can be used now"
                } else {
                    "The committee didn't approve your invitation for ${event.personName}"
                },
                data = mapOf(
                    "type" to if (event.approved) "INVITATION_APPROVED" else "INVITATION_REJECTED",
                    "invitationId" to event.invitationId.toString(),
                ),
            )
        } catch (e: Exception) {
            log.warn("Push notification failed for decided invitation {}", event.invitationId, e)
        }
    }

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
