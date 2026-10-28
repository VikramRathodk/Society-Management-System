package org.devvikram.societymanagement.communication.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.devvikram.societymanagement.communication.model.Notice
import java.time.Instant

data class CreateNoticeRequest(
    @field:NotBlank @field:Size(max = 255) val title: String,
    @field:Size(max = 5000) val description: String? = null,
)

// Omitted fields stay as they are; an empty description clears it.
data class UpdateNoticeRequest(
    @field:Size(max = 255) val title: String? = null,
    @field:Size(max = 5000) val description: String? = null,
)

data class NoticeResponse(
    val id: Long,
    val societyId: Long,
    val title: String,
    val description: String?,
    val postedById: Long,
    val postedByName: String,
    val createdAt: Instant?,
    // Set once edited; updatedById differs from postedById when a moderator changed it.
    val updatedAt: Instant?,
    val updatedById: Long?,
    val updatedByName: String?,
) {
    companion object {
        fun from(notice: Notice) = NoticeResponse(
            id = notice.id!!,
            societyId = notice.society.id!!,
            title = notice.title,
            description = notice.description,
            postedById = notice.postedBy.id!!,
            postedByName = notice.postedBy.name,
            createdAt = notice.createdAt,
            updatedAt = notice.updatedAt,
            updatedById = notice.updatedBy?.id,
            updatedByName = notice.updatedBy?.name,
        )
    }
}
