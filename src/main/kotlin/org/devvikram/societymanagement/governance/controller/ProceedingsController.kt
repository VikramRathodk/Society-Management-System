package org.devvikram.societymanagement.governance.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.governance.dto.AttendanceRequest
import org.devvikram.societymanagement.governance.dto.AttendanceSheetResponse
import org.devvikram.societymanagement.governance.dto.HoldMeetingRequest
import org.devvikram.societymanagement.governance.dto.MeetingResponse
import org.devvikram.societymanagement.governance.dto.OpenVotingRequest
import org.devvikram.societymanagement.governance.dto.ResolutionResponse
import org.devvikram.societymanagement.governance.dto.VoteRequest
import org.devvikram.societymanagement.governance.model.Outcome
import org.devvikram.societymanagement.governance.service.MeetingService
import org.devvikram.societymanagement.governance.service.MeetingViewer
import org.devvikram.societymanagement.governance.service.ProceedingsService
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// Running a meeting (SECRETARY and above, MINUTES.CREATE): attendance, marking it held, opening and
// closing voting, deferring items. Voting itself: anyone who may vote on the item (the owner side of a
// member flat at general meetings, committee members at committee meetings). The resolutions register:
// whoever may see the meetings. Answers return the updated meeting as the caller sees it.
@RestController
class ProceedingsController(
    private val proceedings: ProceedingsService,
    private val meetingService: MeetingService,
    private val meetingController: MeetingController,
    private val accessService: AccessService,
) {
    // ---- Attendance ----

    @GetMapping("/api/meetings/{id}/attendance")
    @RequiresPermission(Permissions.GOVERNANCE_MINUTES_CREATE)
    fun attendance(@PathVariable id: Long): AttendanceSheetResponse {
        requireRunning(meetingService.get(id).societyId)
        return AttendanceSheetResponse.from(proceedings.sheet(id))
    }

    // Exactly these people are present.
    @PutMapping("/api/meetings/{id}/attendance")
    @RequiresPermission(Permissions.GOVERNANCE_MINUTES_CREATE)
    fun setAttendance(@PathVariable id: Long, @RequestBody request: AttendanceRequest): AttendanceSheetResponse {
        val access = requireRunning(meetingService.get(id).societyId)
        return AttendanceSheetResponse.from(proceedings.setAttendance(id, request.userIds.toSet(), access.userId))
    }

    @PostMapping("/api/meetings/{id}/hold")
    @RequiresPermission(Permissions.GOVERNANCE_MINUTES_CREATE)
    fun hold(@PathVariable id: Long, @RequestBody(required = false) request: HoldMeetingRequest?): MeetingResponse {
        val access = requireRunning(meetingService.get(id).societyId)
        proceedings.markHeld(id, request?.chairpersonId, access.userId)
        return meetingController.get(id)
    }

    // ---- Voting ----

    @PostMapping("/api/agenda-items/{itemId}/open-voting")
    @RequiresPermission(Permissions.GOVERNANCE_MINUTES_CREATE)
    fun openVoting(@PathVariable itemId: Long, @RequestBody(required = false) request: OpenVotingRequest?): MeetingResponse {
        val meeting = proceedings.meetingOfItem(itemId)
        val access = requireRunning(meeting.societyId)
        proceedings.openVoting(itemId, request?.proposedBy, request?.secondedBy, access.userId)
        return meetingController.get(meeting.id!!)
    }

    @PostMapping("/api/agenda-items/{itemId}/close-voting")
    @RequiresPermission(Permissions.GOVERNANCE_MINUTES_CREATE)
    fun closeVoting(@PathVariable itemId: Long): MeetingResponse {
        val meeting = proceedings.meetingOfItem(itemId)
        val access = requireRunning(meeting.societyId)
        proceedings.closeVoting(itemId, access.userId)
        return meetingController.get(meeting.id!!)
    }

    @PostMapping("/api/agenda-items/{itemId}/defer")
    @RequiresPermission(Permissions.GOVERNANCE_MINUTES_CREATE)
    fun defer(@PathVariable itemId: Long): MeetingResponse {
        val meeting = proceedings.meetingOfItem(itemId)
        val access = requireRunning(meeting.societyId)
        proceedings.defer(itemId, access.userId)
        return meetingController.get(meeting.id!!)
    }

    // Final once cast; `flatId` only when the caller votes for more than one flat.
    @PostMapping("/api/agenda-items/{itemId}/vote")
    fun vote(@PathVariable itemId: Long, @Valid @RequestBody request: VoteRequest): MeetingResponse {
        val meeting = proceedings.meetingOfItem(itemId)
        val access = accessService.current()
        // A meeting the caller can't see doesn't exist for them.
        meetingService.details(meeting.id!!, viewerOf(access, meeting.societyId))
        proceedings.vote(itemId, request.choice!!, request.flatId, access)
        return meetingController.get(meeting.id!!)
    }

    // ---- Register ----

    // Decided items (passed by default; outcome=all for every one), latest first; q matches every word of
    // the number, title, resolution or meeting.
    @GetMapping("/api/societies/{societyId}/resolutions")
    fun register(
        @PathVariable societyId: Long,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "passed") outcome: String,
    ): List<ResolutionResponse> {
        val viewer = viewerOf(accessService.current(), societyId)
        if (!viewer.seesGeneral && !viewer.seesCommittee && !viewer.organises) {
            throw AccessDeniedException("Missing permission: ${Permissions.GOVERNANCE_MEETING_VIEW} for this society")
        }
        val filter = when (outcome.trim().lowercase()) {
            "all" -> null
            "passed" -> Outcome.PASSED
            "rejected" -> Outcome.REJECTED
            "deferred" -> Outcome.DEFERRED
            else -> throw IllegalArgumentException("outcome must be one of passed, rejected, deferred, all")
        }
        return proceedings.register(societyId, viewer, q, filter).map(ResolutionResponse::from)
    }

    // ---- Helpers ----

    private fun requireRunning(societyId: Long): Access {
        val access = accessService.current()
        if (!access.canSocietyWide(Permissions.GOVERNANCE_MINUTES_CREATE, societyId)) {
            throw AccessDeniedException("Missing permission: ${Permissions.GOVERNANCE_MINUTES_CREATE} for this society")
        }
        return access
    }

    private fun viewerOf(access: Access, societyId: Long) = MeetingViewer(
        userId = access.userId,
        seesGeneral = access.can(Permissions.GOVERNANCE_MEETING_VIEW, societyId),
        seesCommittee = access.canSocietyWide(Permissions.GOVERNANCE_COMMITTEE_VIEW, societyId),
        organises = access.canSocietyWide(Permissions.GOVERNANCE_MEETING_CREATE, societyId),
    )
}
