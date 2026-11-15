package org.devvikram.societymanagement.governance.dto

import jakarta.validation.constraints.NotBlank
import org.devvikram.societymanagement.governance.model.MinutesStatus
import org.devvikram.societymanagement.governance.service.MinutesView
import java.time.Instant

data class SaveMinutesRequest(val body: String? = null)

data class MinutesReasonRequest(@field:NotBlank val reason: String?)

data class AddendumRequest(@field:NotBlank val text: String?)

data class AttachmentInfo(val name: String?, val contentType: String, val sizeBytes: Int)

data class AddendumResponse(val id: Long, val text: String, val createdBy: PersonRef, val createdAt: Instant)

data class MinutesResponse(
    val meetingId: Long,
    val status: MinutesStatus,
    val body: String,
    val attachment: AttachmentInfo?,
    val draftedBy: PersonRef,
    val draftedAt: Instant,
    val updatedAt: Instant?,
    val submittedAt: Instant?,
    val reviewedBy: PersonRef?,
    val reviewReason: String?,
    val approvedBy: PersonRef?,
    val publishedAt: Instant?,
    val announcedNoticeId: Long?,
    val addenda: List<AddendumResponse>,
) {
    companion object {
        fun from(v: MinutesView, names: Map<Long, String>): MinutesResponse {
            val m = v.minutes
            fun ref(id: Long?) = id?.let { PersonRef(it, names[it]) }
            return MinutesResponse(
                meetingId = m.meetingId,
                status = m.status,
                body = m.body,
                attachment = m.attachmentKey?.let { AttachmentInfo(m.attachmentName, m.attachmentType!!, m.attachmentSize!!) },
                draftedBy = PersonRef(m.draftedBy, names[m.draftedBy]),
                draftedAt = m.draftedAt,
                updatedAt = m.updatedAt,
                submittedAt = m.submittedAt,
                reviewedBy = ref(m.reviewedBy),
                reviewReason = m.reviewReason,
                approvedBy = ref(m.approvedBy),
                publishedAt = m.publishedAt,
                announcedNoticeId = m.announcedNoticeId,
                addenda = v.addenda.map { AddendumResponse(it.id!!, it.text, PersonRef(it.createdBy, names[it.createdBy]), it.createdAt) },
            )
        }

        fun people(v: MinutesView): List<Long> =
            listOfNotNull(v.minutes.draftedBy, v.minutes.reviewedBy, v.minutes.approvedBy) + v.addenda.map { it.createdBy }
    }
}
