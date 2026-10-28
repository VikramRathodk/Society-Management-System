package org.devvikram.societymanagement.complaint.service

import org.devvikram.societymanagement.complaint.model.ComplaintCategory
import org.devvikram.societymanagement.complaint.model.ComplaintStatus
import org.devvikram.societymanagement.notification.PushNotifier
import org.devvikram.societymanagement.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

// Carries everything the message needs, so the listener never touches lazy associations.
data class ComplaintStatusChanged(
    val complaintId: Long,
    val raisedById: Long,
    val actorId: Long,
    val category: ComplaintCategory,
    val status: ComplaintStatus,
    val actorName: String,
)

// Runs only after the transaction commits, so nobody hears about a change that was rolled back.
// Best-effort: a failure is logged, never surfaced to the committee member who acted.
@Component
class ComplaintNotificationListener(
    private val userRepository: UserRepository,
    private val pushNotifier: PushNotifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Tells the person who raised the complaint, unless they made the change themselves.
    @TransactionalEventListener
    fun onStatusChanged(event: ComplaintStatusChanged) {
        if (event.raisedById == event.actorId) return
        try {
            val raiser = userRepository.findById(event.raisedById) ?: return
            val token = raiser.fcmToken?.takeIf { raiser.isActive } ?: return
            pushNotifier.send(
                listOf(token),
                title = "Complaint ${event.status.label()}",
                body = "Your ${event.category.name.lowercase()} complaint was marked ${event.status.label()} by ${event.actorName}",
                data = mapOf(
                    "type" to "COMPLAINT_STATUS_CHANGED",
                    "complaintId" to event.complaintId.toString(),
                    "status" to event.status.name,
                ),
            )
        } catch (e: Exception) {
            log.warn("Push notification failed for complaint {} status change", event.complaintId, e)
        }
    }

    private fun ComplaintStatus.label() = name.lowercase().replace('_', ' ')
}
