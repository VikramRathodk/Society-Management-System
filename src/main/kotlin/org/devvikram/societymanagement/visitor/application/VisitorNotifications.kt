package org.devvikram.societymanagement.visitor.application

import org.devvikram.societymanagement.common.notification.PushNotifier
import org.devvikram.societymanagement.user.domain.AppUserRepository
import org.devvikram.societymanagement.user.domain.UserRoleRepository
import org.devvikram.societymanagement.visitor.domain.VisitorStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

// Events carry everything the message needs, so listeners never touch lazy associations.
data class VisitorLogged(
    val entryId: Long,
    val flatId: Long,
    val flatNumber: String,
    val visitorName: String,
    val purpose: String?,
)

data class VisitorResponded(
    val entryId: Long,
    val guardId: Long,
    val flatId: Long,
    val visitorName: String,
    val flatNumber: String,
    val decision: VisitorStatus,
    val responderName: String,
    val asOverride: Boolean,
)

// Runs only after the transaction commits, so nobody is notified about a visitor that was rolled
// back. Best-effort: a failure is logged, never surfaced to the guard or resident who acted.
@Component
class VisitorNotificationListener(
    private val userRoleRepository: UserRoleRepository,
    private val appUserRepository: AppUserRepository,
    private val pushNotifier: PushNotifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Every active user tied to the flat (its residents) is asked to approve or reject.
    @TransactionalEventListener
    fun onLogged(event: VisitorLogged) = bestEffort("visitor ${event.entryId} logged") {
        pushNotifier.send(
            residentTokens(event.flatId),
            title = "Visitor at the gate",
            body = listOfNotNull(event.visitorName, event.purpose).joinToString(" — "),
            data = mapOf("type" to "VISITOR_LOGGED", "visitorId" to event.entryId.toString()),
        )
    }

    // The guard who logged the visitor learns the outcome. On an override the residents are told
    // too, since someone else decided for their flat.
    @TransactionalEventListener
    fun onResponded(event: VisitorResponded) = bestEffort("visitor ${event.entryId} responded") {
        val decision = event.decision.name.lowercase()
        val by = event.responderName + if (event.asOverride) " (override)" else ""
        val data = mapOf(
            "type" to "VISITOR_RESPONDED",
            "visitorId" to event.entryId.toString(),
            "status" to event.decision.name,
            "override" to event.asOverride.toString(),
        )
        appUserRepository.findById(event.guardId)?.takeIf { it.isActive }?.fcmToken?.let { token ->
            pushNotifier.send(
                listOf(token),
                title = "Visitor $decision",
                body = "${event.visitorName} for flat ${event.flatNumber} — $decision by $by",
                data = data,
            )
        }
        if (event.asOverride) {
            pushNotifier.send(
                residentTokens(event.flatId),
                title = "Visitor $decision on your behalf",
                body = "${event.visitorName} — $decision by $by",
                data = data,
            )
        }
    }

    private fun residentTokens(flatId: Long): List<String> =
        userRoleRepository.findByFlatId(flatId)
            .map { it.user }
            .filter { it.isActive }
            .mapNotNull { it.fcmToken }
            .distinct()

    private fun bestEffort(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            log.warn("Push notification failed for {}", what, e)
        }
    }
}
