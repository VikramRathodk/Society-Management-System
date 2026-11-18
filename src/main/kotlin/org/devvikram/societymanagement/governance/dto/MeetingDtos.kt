package org.devvikram.societymanagement.governance.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.devvikram.societymanagement.governance.model.AgendaItem
import org.devvikram.societymanagement.governance.model.MeetingKind
import org.devvikram.societymanagement.governance.model.MeetingStatus
import org.devvikram.societymanagement.governance.model.Outcome
import org.devvikram.societymanagement.governance.model.RsvpResponse
import org.devvikram.societymanagement.governance.model.VoteChoice
import org.devvikram.societymanagement.governance.model.VotingStatus
import org.devvikram.societymanagement.governance.service.AgendaInput
import org.devvikram.societymanagement.governance.service.AttendanceSheet
import org.devvikram.societymanagement.governance.service.ItemState
import org.devvikram.societymanagement.governance.service.MeetingDetails
import org.devvikram.societymanagement.governance.service.MeetingInput
import org.devvikram.societymanagement.governance.service.RegisterEntry
import org.devvikram.societymanagement.governance.service.RsvpSummary
import java.time.Instant

data class AgendaItemRequest(
    @field:NotBlank val title: String?,
    val description: String? = null,
    val resolutionText: String? = null,
    // A policy of the society the resolution refers to.
    val policyId: Long? = null,
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
        agenda = agenda.map { AgendaInput(it.title ?: "", it.description, it.resolutionText, it.policyId) },
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

data class VoteResults(val votesFor: Int, val votesAgainst: Int, val abstained: Int)

data class AgendaItemResponse(
    val id: Long,
    val position: Int,
    val title: String,
    val description: String?,
    val resolutionText: String?,
    val proposedBy: PersonRef?,
    val secondedBy: PersonRef?,
    val votingStatus: VotingStatus,
    val outcome: Outcome?,
    val resolutionNumber: String?,
    // Ballots cast so far, out of `electorate` (member flats or committee members).
    val turnout: Int,
    // Only once voting has closed: the ballot is secret and running totals aren't shown.
    val results: VoteResults?,
    // The caller has voted (for every flat they vote for).
    val youVoted: Boolean,
    val canVote: Boolean,
    // How many flats the caller votes for (more than one: they choose which when voting).
    val yourVotes: Int,
    // General meetings: the caller's flats, labelled, and whether each has voted.
    val yourFlats: List<VoterFlatResponse>,
    // The policy the resolution refers to, if any.
    val policy: PolicyRef?,
) {
    companion object {
        fun from(item: AgendaItem, state: ItemState?, names: Map<Long, String>, policies: Map<Long, String> = emptyMap()) = AgendaItemResponse(
            id = item.id!!,
            position = item.position,
            title = item.title,
            description = item.description,
            resolutionText = item.resolutionText,
            proposedBy = item.proposedBy?.let { PersonRef(it, names[it]) },
            secondedBy = item.secondedBy?.let { PersonRef(it, names[it]) },
            votingStatus = item.votingStatus,
            outcome = item.outcome,
            resolutionNumber = item.resolutionNumber,
            turnout = state?.turnout ?: 0,
            results = if (item.votingStatus == VotingStatus.CLOSED) VoteResults(item.votesFor, item.votesAgainst, item.votesAbstain) else null,
            youVoted = state != null && state.voterKeys.isNotEmpty() && state.votedAs.size == state.voterKeys.size,
            canVote = state?.canVote ?: false,
            yourVotes = state?.voterKeys?.size ?: 0,
            yourFlats = state?.flats.orEmpty().map { VoterFlatResponse(it.id, it.label, it.voted) },
            policy = item.policyId?.let { PolicyRef(it, policies[it]) },
        )
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
    val heldAt: Instant?,
    val chairperson: PersonRef?,
    // Member flats (general meetings) or committee members who may vote.
    val electorate: Int,
    // Frozen once held: the quorum needed and how many were present.
    val quorumRequired: Int?,
    val quorumPresent: Int?,
) {
    companion object {
        fun from(
            d: MeetingDetails,
            names: Map<Long, String>,
            rsvps: RsvpSummary?,
            states: Map<Long, ItemState> = emptyMap(),
            electorate: Int = 0,
            policies: Map<Long, String> = emptyMap(),
        ): MeetingResponse {
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
                agenda = d.agenda.map { AgendaItemResponse.from(it, states[it.id], names, policies) },
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
                heldAt = m.heldAt,
                chairperson = m.chairpersonId?.let { PersonRef(it, names[it]) },
                electorate = electorate,
                quorumRequired = m.quorumRequired,
                quorumPresent = m.quorumPresent,
            )
        }
    }
}

data class RsvpEntryResponse(val user: PersonRef, val response: RsvpResponse, val updatedAt: Instant)

data class RsvpListResponse(val yes: Int, val no: Int, val maybe: Int, val responses: List<RsvpEntryResponse>)

data class AttendanceRequest(val userIds: List<Long> = emptyList())

data class HoldMeetingRequest(val chairpersonId: Long? = null)

data class OpenVotingRequest(val proposedBy: Long? = null, val secondedBy: Long? = null)

data class VoteRequest(@field:NotNull val choice: VoteChoice?, val flatId: Long? = null)

data class QuorumResponse(val members: Int, val required: Int, val present: Int, val met: Boolean)

data class AttendanceRowResponse(
    val user: PersonRef,
    val flatIds: Set<Long>,
    val committee: Boolean,
    val present: Boolean,
    val rsvp: String?,
)

data class AttendanceSheetResponse(
    val meetingId: Long,
    val status: MeetingStatus,
    val quorum: QuorumResponse,
    val people: List<AttendanceRowResponse>,
) {
    companion object {
        fun from(sheet: AttendanceSheet) = AttendanceSheetResponse(
            meetingId = sheet.meeting.id!!,
            status = sheet.meeting.status,
            quorum = QuorumResponse(sheet.quorum.members, sheet.quorum.required, sheet.quorum.present, sheet.quorum.met),
            people = sheet.rows.map { AttendanceRowResponse(PersonRef(it.userId, it.name), it.flats, it.committee, it.present, it.rsvp) },
        )
    }
}

// A decided item in the resolutions register.
data class ResolutionResponse(
    val itemId: Long,
    val resolutionNumber: String?,
    val title: String,
    val resolutionText: String?,
    val outcome: Outcome,
    val results: VoteResults?,
    val decidedAt: Instant?,
    val meetingId: Long,
    val meetingKind: MeetingKind,
    val meetingTitle: String,
    val meetingAt: Instant,
) {
    companion object {
        fun from(e: RegisterEntry) = ResolutionResponse(
            itemId = e.item.id!!,
            resolutionNumber = e.item.resolutionNumber,
            title = e.item.title,
            resolutionText = e.item.resolutionText,
            outcome = e.item.outcome!!,
            results = if (e.item.votingStatus == VotingStatus.CLOSED) VoteResults(e.item.votesFor, e.item.votesAgainst, e.item.votesAbstain) else null,
            decidedAt = e.item.votingClosedAt,
            meetingId = e.meeting.id!!,
            meetingKind = e.meeting.kind,
            meetingTitle = e.meeting.title,
            meetingAt = e.meeting.scheduledAt,
        )
    }
}

data class VoterFlatResponse(val id: Long, val label: String, val voted: Boolean)

data class PolicyRef(val id: Long, val title: String?)
