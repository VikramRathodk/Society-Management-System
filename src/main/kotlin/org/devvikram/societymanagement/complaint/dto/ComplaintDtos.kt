package org.devvikram.societymanagement.complaint.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.devvikram.societymanagement.complaint.model.Complaint
import org.devvikram.societymanagement.complaint.model.ComplaintActivity
import org.devvikram.societymanagement.complaint.model.ComplaintCategory
import org.devvikram.societymanagement.complaint.model.ComplaintStatus
import java.time.Instant

data class RaiseComplaintRequest(
    @field:NotNull val flatId: Long?,
    @field:NotNull val category: ComplaintCategory?,
    @field:NotBlank @field:Size(max = 5000) val description: String,
)

// Omitted fields stay as they are.
data class UpdateComplaintRequest(
    val category: ComplaintCategory? = null,
    @field:Size(max = 5000) val description: String? = null,
)

// Optional body of a status change: e.g. how it was fixed, or why it was reopened.
data class ChangeComplaintStatusRequest(
    @field:Size(max = 2000) val note: String? = null,
)

data class ComplaintActivityResponse(
    // null for the step that raised the complaint.
    val fromStatus: ComplaintStatus?,
    val toStatus: ComplaintStatus,
    val note: String?,
    val actorId: Long,
    val actorName: String,
    val createdAt: Instant?,
) {
    companion object {
        fun from(activity: ComplaintActivity) = ComplaintActivityResponse(
            fromStatus = activity.fromStatus,
            toStatus = activity.toStatus,
            note = activity.note,
            actorId = activity.actor.id!!,
            actorName = activity.actor.name,
            createdAt = activity.createdAt,
        )
    }
}

data class ComplaintResponse(
    val id: Long,
    val societyId: Long,
    val flatId: Long,
    val flatNumber: String,
    val category: ComplaintCategory,
    val description: String,
    val status: ComplaintStatus,
    val raisedById: Long,
    val raisedByName: String,
    val createdAt: Instant?,
    // Last edit or status change.
    val updatedAt: Instant?,
    val updatedById: Long?,
    val updatedByName: String?,
) {
    companion object {
        fun from(complaint: Complaint) = ComplaintResponse(
            id = complaint.id!!,
            societyId = complaint.society.id!!,
            flatId = complaint.flat.id!!,
            flatNumber = complaint.flat.flatNumber,
            category = complaint.category,
            description = complaint.description,
            status = complaint.status,
            raisedById = complaint.raisedBy.id!!,
            raisedByName = complaint.raisedBy.name,
            createdAt = complaint.createdAt,
            updatedAt = complaint.updatedAt,
            updatedById = complaint.updatedBy?.id,
            updatedByName = complaint.updatedBy?.name,
        )
    }
}
