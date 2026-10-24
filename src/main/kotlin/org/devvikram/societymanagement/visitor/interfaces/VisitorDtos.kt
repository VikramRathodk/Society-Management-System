package org.devvikram.societymanagement.visitor.interfaces

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.devvikram.societymanagement.visitor.domain.VisitorEntry
import org.devvikram.societymanagement.visitor.domain.VisitorStatus
import java.time.Instant

// Multipart form fields (the photo travels as a separate "photo" part). Nullable so a missing
// field surfaces as a validation error rather than a binding failure.
data class LogVisitorRequest(
    @field:NotNull val flatId: Long?,
    @field:NotBlank @field:Size(max = 255) val visitorName: String?,
    @field:Size(max = 15) val visitorPhone: String? = null,
    @field:Size(max = 100) val purpose: String? = null,
)

data class VisitorResponse(
    val id: Long,
    val societyId: Long,
    val flatId: Long,
    val flatNumber: String,
    val visitorName: String,
    val visitorPhone: String?,
    val purpose: String?,
    // Relative path to GET the photo (same auth as the entry), or null if none was uploaded.
    val photoUrl: String?,
    val status: VisitorStatus,
    val loggedById: Long,
    val loggedByName: String,
    val respondedById: Long?,
    val respondedByName: String?,
    // True when respondedBy is a society authority who decided on the flat's behalf, not a resident.
    val overridden: Boolean,
    val loggedAt: Instant?,
    val respondedAt: Instant?,
    val enteredAt: Instant?,
    val exitedAt: Instant?,
) {
    companion object {
        fun from(entry: VisitorEntry) = VisitorResponse(
            id = entry.id!!,
            societyId = entry.society.id!!,
            flatId = entry.flat.id!!,
            flatNumber = entry.flat.flatNumber,
            visitorName = entry.visitorName,
            visitorPhone = entry.visitorPhone,
            purpose = entry.purpose,
            photoUrl = entry.photoKey?.let { "/api/visitors/${entry.id}/photo" },
            status = entry.status,
            loggedById = entry.loggedBy.id!!,
            loggedByName = entry.loggedBy.name,
            respondedById = entry.respondedBy?.id,
            respondedByName = entry.respondedBy?.name,
            overridden = entry.respondedAsOverride,
            loggedAt = entry.loggedAt,
            respondedAt = entry.respondedAt,
            enteredAt = entry.enteredAt,
            exitedAt = entry.exitedAt,
        )
    }
}
