package org.devvikram.societymanagement.governance.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.governance.dto.AdjournMeetingRequest
import org.devvikram.societymanagement.governance.dto.CancelMeetingRequest
import org.devvikram.societymanagement.governance.dto.MeetingRequest
import org.devvikram.societymanagement.governance.dto.MeetingResponse
import org.devvikram.societymanagement.governance.dto.PersonRef
import org.devvikram.societymanagement.governance.dto.RsvpEntryResponse
import org.devvikram.societymanagement.governance.dto.RsvpListResponse
import org.devvikram.societymanagement.governance.dto.RsvpRequest
import org.devvikram.societymanagement.governance.dto.SendNoticeRequest
import org.devvikram.societymanagement.governance.model.MeetingKind
import org.devvikram.societymanagement.governance.service.MeetingDetails
import org.devvikram.societymanagement.governance.service.MeetingService
import org.devvikram.societymanagement.governance.service.MeetingView
import org.devvikram.societymanagement.governance.service.MeetingViewer
import org.devvikram.societymanagement.governance.service.ProceedingsService
import org.devvikram.societymanagement.policy.service.PolicyService
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// Who may do what with meetings (all within the meeting's society):
//  - residents and the committee (MEETING.VIEW) see announced AGMs and SGMs and RSVP to them;
//  - the committee (COMMITTEE.VIEW, society-wide) sees announced committee meetings and RSVPs to them;
//  - SECRETARY and above prepare meetings and see drafts (MEETING.CREATE), and send the notice, cancel
//    and adjourn (MEETING.UPDATE).
// A meeting the caller may not see answers 404.
@RestController
class MeetingController(
    private val meetingService: MeetingService,
    private val proceedings: ProceedingsService,
    private val policyService: PolicyService,
    private val accessService: AccessService,
) {
    @GetMapping("/api/societies/{societyId}/meetings")
    fun list(
        @PathVariable societyId: Long,
        @RequestParam(defaultValue = "upcoming") view: String,
        @RequestParam(required = false) kind: MeetingKind?,
    ): List<MeetingResponse> {
        val viewer = viewer(societyId)
        if (!viewer.seesGeneral && !viewer.seesCommittee && !viewer.organises) {
            throw AccessDeniedException("Missing permission: ${Permissions.GOVERNANCE_MEETING_VIEW} for this society")
        }
        val details = meetingService.list(societyId, parseView(view), kind, viewer)
        val names = meetingService.names(details.flatMap(::people))
        val access = accessService.current()
        return details.map { respond(it, names, viewer, access) }
    }

    @PostMapping("/api/societies/{societyId}/meetings")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.GOVERNANCE_MEETING_CREATE)
    fun create(@PathVariable societyId: Long, @Valid @RequestBody request: MeetingRequest): MeetingResponse {
        val access = require(Permissions.GOVERNANCE_MEETING_CREATE, societyId)
        val id = meetingService.create(societyId, request.toInput(), access.userId)
        return view(id, viewerOf(access, societyId))
    }

    @GetMapping("/api/meetings/{id}")
    fun get(@PathVariable id: Long): MeetingResponse = view(id, viewer(meetingService.get(id).societyId))

    // Replaces a draft whole, agenda included.
    @PutMapping("/api/meetings/{id}")
    @RequiresPermission(Permissions.GOVERNANCE_MEETING_CREATE)
    fun update(@PathVariable id: Long, @Valid @RequestBody request: MeetingRequest): MeetingResponse {
        val access = requireForMeeting(Permissions.GOVERNANCE_MEETING_CREATE, id)
        meetingService.update(id, request.toInput(), access.userId)
        return view(id, viewerOf(access, meetingService.get(id).societyId))
    }

    @DeleteMapping("/api/meetings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.GOVERNANCE_MEETING_CREATE)
    fun delete(@PathVariable id: Long) {
        requireForMeeting(Permissions.GOVERNANCE_MEETING_CREATE, id)
        meetingService.delete(id)
    }

    @PostMapping("/api/meetings/{id}/send-notice")
    @RequiresPermission(Permissions.GOVERNANCE_MEETING_UPDATE)
    fun sendNotice(@PathVariable id: Long, @RequestBody(required = false) request: SendNoticeRequest?): MeetingResponse {
        val access = requireForMeeting(Permissions.GOVERNANCE_MEETING_UPDATE, id)
        meetingService.sendNotice(id, request?.shortNoticeReason, access.userId)
        return view(id, viewerOf(access, meetingService.get(id).societyId))
    }

    @PostMapping("/api/meetings/{id}/cancel")
    @RequiresPermission(Permissions.GOVERNANCE_MEETING_UPDATE)
    fun cancel(@PathVariable id: Long, @Valid @RequestBody request: CancelMeetingRequest): MeetingResponse {
        val access = requireForMeeting(Permissions.GOVERNANCE_MEETING_UPDATE, id)
        meetingService.cancel(id, request.reason!!, access.userId)
        return view(id, viewerOf(access, meetingService.get(id).societyId))
    }

    // Answers the new meeting.
    @PostMapping("/api/meetings/{id}/adjourn")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.GOVERNANCE_MEETING_UPDATE)
    fun adjourn(@PathVariable id: Long, @Valid @RequestBody request: AdjournMeetingRequest): MeetingResponse {
        val access = requireForMeeting(Permissions.GOVERNANCE_MEETING_UPDATE, id)
        val next = meetingService.adjourn(id, request.scheduledAt!!, request.place, request.onlineLink, request.reason!!, access.userId)
        return view(next, viewerOf(access, meetingService.get(next).societyId))
    }

    @PutMapping("/api/meetings/{id}/rsvp")
    fun rsvp(@PathVariable id: Long, @Valid @RequestBody request: RsvpRequest): MeetingResponse {
        val viewer = viewer(meetingService.get(id).societyId)
        meetingService.details(id, viewer) // 404 for a meeting the caller can't see
        meetingService.rsvp(id, request.response!!, viewer)
        return view(id, viewer)
    }

    // Who replied what (organisers).
    @GetMapping("/api/meetings/{id}/rsvps")
    @RequiresPermission(Permissions.GOVERNANCE_MEETING_CREATE)
    fun rsvps(@PathVariable id: Long): RsvpListResponse {
        requireForMeeting(Permissions.GOVERNANCE_MEETING_CREATE, id)
        val summary = meetingService.rsvps(id)
        val names = meetingService.names(summary.responses.map { it.userId })
        return RsvpListResponse(
            summary.yes,
            summary.no,
            summary.maybe,
            summary.responses.map { RsvpEntryResponse(PersonRef(it.userId, names[it.userId]), it.response, it.updatedAt) },
        )
    }

    // ---- Helpers ----

    private fun view(id: Long, viewer: MeetingViewer): MeetingResponse {
        val details = meetingService.details(id, viewer)
        return respond(details, meetingService.names(people(details)), viewer, accessService.current())
    }

    private fun respond(details: MeetingDetails, names: Map<Long, String>, viewer: MeetingViewer, access: Access) =
        MeetingResponse.from(
            details,
            names,
            rsvpsFor(details, viewer),
            proceedings.states(details.meeting, details.agenda, access),
            proceedings.electorate(details.meeting),
            details.agenda.mapNotNull { it.policyId }.distinct()
                .mapNotNull { id -> runCatching { id to policyService.get(id).title }.getOrNull() }.toMap(),
        )

    private fun people(d: MeetingDetails): List<Long> =
        listOfNotNull(d.meeting.createdBy, d.meeting.noticeSentBy, d.meeting.chairpersonId) +
            d.agenda.flatMap { listOfNotNull(it.proposedBy, it.secondedBy) }

    private fun rsvpsFor(details: MeetingDetails, viewer: MeetingViewer) =
        if (viewer.organises) meetingService.rsvps(details.meeting.id!!) else null

    private fun viewer(societyId: Long) = viewerOf(accessService.current(), societyId)

    private fun viewerOf(access: Access, societyId: Long) = MeetingViewer(
        userId = access.userId,
        seesGeneral = access.can(Permissions.GOVERNANCE_MEETING_VIEW, societyId),
        seesCommittee = access.canSocietyWide(Permissions.GOVERNANCE_COMMITTEE_VIEW, societyId),
        organises = access.canSocietyWide(Permissions.GOVERNANCE_MEETING_CREATE, societyId),
    )

    private fun require(permission: String, societyId: Long): Access {
        val access = accessService.current()
        if (!access.canSocietyWide(permission, societyId)) throw AccessDeniedException("Missing permission: $permission for this society")
        return access
    }

    private fun requireForMeeting(permission: String, meetingId: Long) = require(permission, meetingService.get(meetingId).societyId)

    private fun parseView(raw: String): MeetingView =
        MeetingView.entries.firstOrNull { it.name == raw.trim().uppercase() }
            ?: throw IllegalArgumentException("view must be one of upcoming, past, drafts")
}
