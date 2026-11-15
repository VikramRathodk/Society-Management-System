package org.devvikram.societymanagement.policy.service

import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.notification.PushNotifier
import org.devvikram.societymanagement.user.repository.UserRepository
import org.devvikram.societymanagement.user.repository.UserRoleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.time.LocalDate

// Events carry everything the message needs, so listeners never touch the database for the policy.
data class PolicySubmitted(
    val policyId: Long,
    val societyId: Long,
    val title: String,
    val number: Int,
    val authorId: Long,
    val authorName: String,
)

data class PolicySentBack(val policyId: Long, val title: String, val number: Int, val authorId: Long, val reason: String)

data class PolicyApproved(val policyId: Long, val title: String, val number: Int, val authorId: Long)

data class PolicyPublished(
    val policyId: Long,
    val societyId: Long,
    val title: String,
    val number: Int,
    val effectiveFrom: LocalDate,
    val upcoming: Boolean,
    val exceptUserIds: Set<Long>,
)

// After commit, best-effort: a failed push is logged, never surfaced to whoever acted. Nothing reaches
// residents of a society still in setup, since flat roles exist only after go-live.
@Component
class PolicyNotificationListener(
    private val userRepository: UserRepository,
    private val userRoleRepository: UserRoleRepository,
    private val accessService: AccessService,
    private val pushNotifier: PushNotifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // To everyone who may approve policies in the society, except the author.
    @TransactionalEventListener
    fun onSubmitted(event: PolicySubmitted) = safely("submitted", event.policyId) {
        val roles = userRoleRepository.findByUserIds(userRoleRepository.findUserIdsBySocietyIds(listOf(event.societyId)))
        val approverIds = roles.groupBy { it.user.id!! }
            .filter { (userId, userRoles) ->
                userId != event.authorId && accessService.accessFor(userId, userRoles.map { it.toGrant() })
                    .canSocietyWide(Permissions.POLICY_RULE_APPROVE, event.societyId)
            }
            .keys
        send(
            approverIds,
            title = "Policy to approve",
            body = "${event.authorName} submitted ${label(event.title, event.number)}",
            data = mapOf("type" to "POLICY_SUBMITTED", "policyId" to event.policyId.toString()),
        )
    }

    @TransactionalEventListener
    fun onSentBack(event: PolicySentBack) = safely("sent back", event.policyId) {
        send(
            listOf(event.authorId),
            title = "Policy sent back",
            body = "${label(event.title, event.number)}: ${event.reason}",
            data = mapOf("type" to "POLICY_SENT_BACK", "policyId" to event.policyId.toString()),
        )
    }

    @TransactionalEventListener
    fun onApproved(event: PolicyApproved) = safely("approved", event.policyId) {
        send(
            listOf(event.authorId),
            title = "Policy approved",
            body = "${label(event.title, event.number)} is approved and published",
            data = mapOf("type" to "POLICY_APPROVED", "policyId" to event.policyId.toString()),
        )
    }

    // Everyone holding a role in the society.
    @TransactionalEventListener
    fun onPublished(event: PolicyPublished) = safely("published", event.policyId) {
        val userIds = userRoleRepository.findUserIdsBySocietyIds(listOf(event.societyId)) - event.exceptUserIds
        val action = if (event.number == 1) "New rule" else "Rule updated"
        val body = if (event.upcoming) "${event.title}, from ${event.effectiveFrom}" else event.title
        send(
            userIds,
            title = action,
            body = body,
            data = mapOf(
                "type" to "POLICY_PUBLISHED",
                "policyId" to event.policyId.toString(),
                "version" to event.number.toString(),
            ),
        )
    }

    private fun label(title: String, number: Int) = if (number == 1) title else "$title (version $number)"

    private fun send(userIds: Collection<Long>, title: String, body: String, data: Map<String, String>) {
        if (userIds.isEmpty()) return
        val tokens = userRepository.findAllById(userIds).filter { it.isActive }.mapNotNull { it.fcmToken }.distinct()
        if (tokens.isNotEmpty()) pushNotifier.send(tokens, title = title, body = body, data = data)
    }

    private fun safely(what: String, policyId: Long, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn("Push notification failed for policy {} {}", policyId, what, e)
        }
    }
}
