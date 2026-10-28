package org.devvikram.societymanagement.complaint.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.complaint.model.Complaint
import org.devvikram.societymanagement.complaint.model.ComplaintCategory
import org.devvikram.societymanagement.complaint.model.ComplaintStatus
import org.devvikram.societymanagement.complaint.repository.ComplaintRepository
import org.devvikram.societymanagement.complaint.repository.ComplaintSearch
import org.devvikram.societymanagement.society.repository.FlatRepository
import org.devvikram.societymanagement.user.repository.UserRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class RaiseComplaintCommand(
    val flatId: Long,
    val category: ComplaintCategory,
    val description: String,
)

// null leaves a field unchanged.
data class UpdateComplaintCommand(
    val category: ComplaintCategory? = null,
    val description: String? = null,
)

// Complaint lifecycle and its invariants. *Who* may act (resident of the flat, author, committee) is
// decided by the caller (ComplaintController), not here. Write methods return ids; callers re-read
// through getById so the response has every association loaded.
@Service
class ComplaintService(
    private val complaintRepository: ComplaintRepository,
    private val flatRepository: FlatRepository,
    private val userRepository: UserRepository,
    private val events: ApplicationEventPublisher,
) {
    @Transactional
    fun raise(command: RaiseComplaintCommand, raiserId: Long): Long {
        require(command.description.isNotBlank()) { "Description must not be blank" }
        val flat = flatRepository.findById(command.flatId)
            ?: throw NoSuchElementException("Flat ${command.flatId} not found")
        val raiser = userRepository.findById(raiserId) ?: throw NoSuchElementException("User $raiserId not found")
        val complaint = complaintRepository.save(
            Complaint(
                society = flat.wing.society,
                flat = flat,
                category = command.category,
                description = command.description.trim(),
                raisedBy = raiser,
            )
        )
        return complaint.id!!
    }

    // The author may change a complaint only until it is resolved.
    @Transactional
    fun update(id: Long, command: UpdateComplaintCommand, editorId: Long) {
        val complaint = getById(id)
        requireEditable(complaint, "edited")
        command.category?.let { complaint.category = it }
        command.description?.let {
            require(it.isNotBlank()) { "Description must not be blank" }
            complaint.description = it.trim()
        }
        complaint.updatedBy = userRepository.findById(editorId) ?: throw NoSuchElementException("User $editorId not found")
        complaint.updatedAt = Instant.now()
        complaintRepository.save(complaint)
    }

    // Withdrawing removes the complaint; also only until it is resolved.
    @Transactional
    fun withdraw(id: Long) {
        val complaint = getById(id)
        requireEditable(complaint, "withdrawn")
        complaintRepository.delete(complaint)
    }

    // Moves the complaint to `to` if it is in a state that allows it. The conditional update makes the
    // check and the change one step, so concurrent moves can't both apply.
    @Transactional
    fun changeStatus(id: Long, to: ComplaintStatus, actorId: Long) {
        val from = ALLOWED_FROM[to] ?: throw IllegalArgumentException("A complaint cannot be moved to $to")
        val actor = userRepository.findById(actorId) ?: throw NoSuchElementException("User $actorId not found")
        if (!complaintRepository.changeStatus(id, from, to, actor, Instant.now())) {
            throw ConflictException("Complaint is ${getById(id).status}; it cannot be moved to $to")
        }
        val complaint = getById(id)
        events.publishEvent(
            ComplaintStatusChanged(
                complaint.id!!, complaint.raisedBy.id!!, actor.id!!, complaint.category, to, actor.name,
            )
        )
    }

    fun getById(id: Long): Complaint =
        complaintRepository.findById(id) ?: throw NoSuchElementException("Complaint $id not found")

    fun search(search: ComplaintSearch): List<Complaint> = complaintRepository.search(search)

    private fun requireEditable(complaint: Complaint, action: String) {
        if (complaint.status !in EDITABLE) {
            throw ConflictException("Complaint is ${complaint.status} and can no longer be $action")
        }
    }

    companion object {
        val EDITABLE = setOf(ComplaintStatus.OPEN, ComplaintStatus.IN_PROGRESS)

        // Target status → the states it may be reached from.
        val ALLOWED_FROM: Map<ComplaintStatus, Set<ComplaintStatus>> = mapOf(
            ComplaintStatus.IN_PROGRESS to setOf(ComplaintStatus.OPEN),
            ComplaintStatus.RESOLVED to setOf(ComplaintStatus.OPEN, ComplaintStatus.IN_PROGRESS),
            ComplaintStatus.OPEN to setOf(ComplaintStatus.RESOLVED),
            ComplaintStatus.CLOSED to setOf(ComplaintStatus.RESOLVED),
        )
    }
}
