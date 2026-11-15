package org.devvikram.societymanagement.governance.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.devvikram.societymanagement.governance.model.AgendaItem
import org.devvikram.societymanagement.governance.model.MeetingKind
import org.devvikram.societymanagement.governance.model.MeetingStatus
import org.devvikram.societymanagement.governance.model.RsvpResponse
import org.devvikram.societymanagement.governance.service.AgendaInput
import org.devvikram.societymanagement.governance.service.MeetingDetails
import org.devvikram.societymanagement.governance.service.MeetingInput
import org.devvikram.societymanagement.governance.service.RsvpSummary
import java.time.Instant

data class AgendaItemRequest(
    @field:NotBlank val title: String?,
    val description: String? = null,
    val resolutionText: String? = null,
)

// Create a meeting, or replace a draft whole.
data class MeetingRequest(
    @field:NotNull val kind: MeetingKind?,
    @field:NotBlank val title: String?,
    val description: String? = null,
    @field:NotNull val scheduledAt: Instant?,
    val place: String? = null,
    val onlineLink: String? = null,
    val agenda: List<AgendaItemRequest> = emptyList(),
) {
    fun toInput() = MeetingInput(
        kind = kind!!,
        title = title!!,
        description = description,
        scheduledAt = scheduledAt!!,
        place = place,
        onlineLink = onlineLink,
        agenda = agenda.map { AgendaInput(it.title ?: "", it.description, it.resolutionText) },
    )
}

data class SendNoticeRequest(val shortNoticeReason: String? = null)

data class CancelMeetingRequest(@field:NotBlank val reason: String?)

data class AdjournMeetingRequest(
    @field:NotNull val scheduledAt: Instant?,
    val place: String? = null,
    val onlineLink: String? = null,
    @field:NotBlank val reason: String?,
)

data class RsvpRequest(@field:NotNull val response: RsvpResponse?)

data class PersonRef(val id: Long, val name: String?)

data class AgendaItemResponse(
    val id: Long,
    val position: Int,
    val title: String,
    val description: String?,
    val resolutionText: String?,
) {
    companion object {
        fun from(item: AgendaItem) = AgendaItemResponse(item.id!!, item.position, item.title, item.description, item.resolutionText)
    }
}

data class RsvpCounts(val yes: Int, val no: Int, val maybe: Int)

data class MeetingResponse(
    val id: Long,
    val societyId: Long,
    val kind: MeetingKind,
    val general: Boolean,
    val title: String,
    val description: String?,
    val scheduledAt: Instant,
    val place: String?,
    val onlineLink: String?,
    val status: MeetingStatus,
    val agenda: List<AgendaItemResponse>,
    // The society's notice period for this kind of meeting.
    val noticeDays: Long,
    val noticeSentAt: Instant?,
    val noticeSentBy: PersonRef?,
    val shortNoticeReason: String?,
    val cancelledAt: Instant?,
    val cancelReason: String?,
    val adjournedFromId: Long?,
    val adjournedToId: Long?,
    val adjournReason: String?,
    val yourRsvp: RsvpResponse?,
    val canRsvp: Boolean,
    // Only for the organisers.
    val rsvpCounts: RsvpCounts?,
    val createdBy: PersonRef,
    val createdAt: Instant,
) {
    companion object {
        fun from(d: MeetingDetails, names: Map<Long, String>, rsvps: RsvpSummary?): MeetingResponse {
            val m = d.meeting
            return MeetingResponse(
                id = m.id!!,
                societyId = m.societyId,
                kind = m.kind,
                general = m.kind.general,
                title = m.title,
                description = m.description,
                scheduledAt = m.scheduledAt,
                place = m.place,
                onlineLink = m.onlineLink,
                status = m.status,
                agenda = d.agenda.map(AgendaItemResponse::from),
                noticeDays = d.noticeDays,
                noticeSentAt = m.noticeSentAt,
                noticeSentBy = m.noticeSentBy?.let { PersonRef(it, names[it]) },
                shortNoticeReason = m.shortNoticeReason,
                cancelledAt = m.cancelledAt,
                cancelReason = m.cancelReason,
                adjournedFromId = m.adjournedFromId,
                adjournedToId = m.adjournedToId,
                adjournReason = m.adjournReason,
                yourRsvp = d.yourRsvp,
                canRsvp = d.canRsvp,
                rsvpCounts = rsvps?.let { RsvpCounts(it.yes, it.no, it.maybe) },
                createdBy = PersonRef(m.createdBy, names[m.createdBy]),
                createdAt = m.createdAt,
            )
        }
    }
}

data class RsvpEntryResponse(val user: PersonRef, val response: RsvpResponse, val updatedAt: Instant)

data class RsvpListResponse(val yes: Int, val no: Int, val maybe: Int, val responses: List<RsvpEntryResponse>)
