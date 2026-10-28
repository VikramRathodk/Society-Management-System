package org.devvikram.societymanagement.complaint.service

import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.service.PermissionService
import org.devvikram.societymanagement.complaint.model.ComplaintCategory
import org.devvikram.societymanagement.complaint.model.ComplaintStatus
import org.devvikram.societymanagement.notification.PushNotifier
import org.devvikram.societymanagement.user.repository.UserRepository
import org.devvikram.societymanagement.user.repository.UserRoleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

// Events carry everything the message needs, so listeners never touch lazy associations.
data class ComplaintRaised(
    val complaintId: Long,
    val societyId: Long,
    val raisedById: Long,
    val flatNumber: String,
    val category: ComplaintCategory,
)

data class ComplaintStatusChanged(
    val complaintId: Long,
    val raisedById: Long,
    val actorId: Long,
    val category: ComplaintCategory,
    val status: ComplaintStatus,
    val actorName: String,
    val note: String? = null,
)

// Runs only after the transaction commits, so nobody hears about a complaint that was rolled back.
// Best-effort: a failure is logged, never surfaced to the person who acted.
@Component
class ComplaintNotificationListener(
    private val userRoleRepository: UserRoleRepository,
    private val userRepository: UserRepository,
    private val permissionService: PermissionService,
    private val pushNotifier: PushNotifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Tells whoever manages the society's complaints: holders of a society-wide role (not tied to a flat)
    // in this society that grants COMPLAINT.RESOLUTION.APPROVE. Platform roles are not told about every
    // society's complaints.
    @TransactionalEventListener
    fun onRaised(event: ComplaintRaised) = bestEffort("complaint ${event.complaintId} raised") {
        val managerIds = userRoleRepository.findByUserIds(userRoleRepository.findUserIdsBySocietyIds(listOf(event.societyId)))
            .filter { it.society?.id == event.societyId && it.flat == null }
            .filter { Permissions.COMPLAINT_RESOLUTION_APPROVE in permissionService.getPermissionsForRole(it.role.code) }
            .map { it.user.id!! }
            .toSet() - event.raisedById
        val tokens = userRepository.findAllById(managerIds)
            .filter { it.isActive }
            .mapNotNull { it.fcmToken }
            .distinct()
        pushNotifier.send(
            tokens,
            title = "New complaint",
            body = "${event.category.label()} complaint from flat ${event.flatNumber}",
            data = mapOf("type" to "COMPLAINT_RAISED", "complaintId" to event.complaintId.toString()),
        )
    }

    // Tells the person who raised the complaint, unless they made the change themselves.
    @TransactionalEventListener
    fun onStatusChanged(event: ComplaintStatusChanged) = bestEffort("complaint ${event.complaintId} status change") {
        if (event.raisedById == event.actorId) return@bestEffort
        val raiser = userRepository.findById(event.raisedById) ?: return@bestEffort
        val token = raiser.fcmToken?.takeIf { raiser.isActive } ?: return@bestEffort
        val summary = "Your ${event.category.label()} complaint was marked ${event.status.label()} by ${event.actorName}"
        pushNotifier.send(
            listOf(token),
            title = "Complaint ${event.status.label()}",
            body = event.note?.let { "$summary: ${it.take(NOTE_PREVIEW)}" } ?: summary,
            data = mapOf(
                "type" to "COMPLAINT_STATUS_CHANGED",
                "complaintId" to event.complaintId.toString(),
                "status" to event.status.name,
            ),
        )
    }

    private fun ComplaintStatus.label() = name.lowercase().replace('_', ' ')
    private fun ComplaintCategory.label() = name.lowercase()

    private fun bestEffort(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            log.warn("Push notification failed for {}", what, e)
        }
    }

    private companion object {
        const val NOTE_PREVIEW = 100
    }
}
