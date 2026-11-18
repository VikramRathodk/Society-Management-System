package org.devvikram.societymanagement.governance.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.governance.dto.AddendumRequest
import org.devvikram.societymanagement.governance.dto.MinutesReasonRequest
import org.devvikram.societymanagement.governance.dto.MinutesResponse
import org.devvikram.societymanagement.governance.dto.SaveMinutesRequest
import org.devvikram.societymanagement.governance.service.MeetingService
import org.devvikram.societymanagement.governance.service.MeetingViewer
import org.devvikram.societymanagement.governance.service.MinutesActor
import org.devvikram.societymanagement.governance.service.MinutesService
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

// Minutes of a meeting, under the meeting: whoever may see the meeting reads its published minutes;
// SECRETARY and above (MINUTES.CREATE) draft, attach, submit, add corrections and announce; CHAIRMAN and
// above (MINUTES.APPROVE) send back or approve, never their own draft. A meeting the caller can't see
// answers 404, and so do minutes they may not see yet.
@RestController
class MinutesController(
    private val minutesService: MinutesService,
    private val meetingService: MeetingService,
    private val accessService: AccessService,
) {
    @GetMapping("/api/meetings/{id}/minutes")
    fun get(@PathVariable id: Long): MinutesResponse = view(id, actor(id))

    // Writes the draft, starting the minutes if needed (a held meeting).
    @PutMapping("/api/meetings/{id}/minutes")
    fun save(@PathVariable id: Long, @RequestBody request: SaveMinutesRequest): MinutesResponse {
        val actor = actor(id)
        minutesService.save(id, request.body.orEmpty(), actor)
        return view(id, actor)
    }

    @PutMapping("/api/meetings/{id}/minutes/attachment", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun setAttachment(@PathVariable id: Long, @RequestPart("file") file: MultipartFile): MinutesResponse {
        val actor = actor(id)
        minutesService.setAttachment(id, file.bytes, file.originalFilename, actor)
        return view(id, actor)
    }

    @DeleteMapping("/api/meetings/{id}/minutes/attachment")
    fun removeAttachment(@PathVariable id: Long): MinutesResponse {
        val actor = actor(id)
        minutesService.removeAttachment(id, actor)
        return view(id, actor)
    }

    @GetMapping("/api/meetings/{id}/minutes/attachment")
    fun attachment(@PathVariable id: Long): ResponseEntity<ByteArray> {
        val (minutes, file) = minutesService.attachment(id, actor(id))
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.contentType))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline().filename(minutes.attachmentName ?: "minutes-$id", Charsets.UTF_8).build().toString(),
            )
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(file.data)
    }

    @PostMapping("/api/meetings/{id}/minutes/submit")
    fun submit(@PathVariable id: Long): MinutesResponse {
        val actor = actor(id)
        minutesService.submit(id, actor)
        return view(id, actor)
    }

    @PostMapping("/api/meetings/{id}/minutes/send-back")
    fun sendBack(@PathVariable id: Long, @Valid @RequestBody request: MinutesReasonRequest): MinutesResponse {
        val actor = actor(id)
        minutesService.sendBack(id, request.reason!!, actor)
        return view(id, actor)
    }

    @PostMapping("/api/meetings/{id}/minutes/approve")
    fun approve(@PathVariable id: Long): MinutesResponse {
        val actor = actor(id)
        minutesService.approve(id, actor)
        return view(id, actor)
    }

    @PostMapping("/api/meetings/{id}/minutes/addenda")
    @ResponseStatus(HttpStatus.CREATED)
    fun addAddendum(@PathVariable id: Long, @Valid @RequestBody request: AddendumRequest): MinutesResponse {
        val actor = actor(id)
        minutesService.addAddendum(id, request.text!!, actor)
        return view(id, actor)
    }

    // Posts "Minutes published" on the notice board (general meetings, once).
    @PostMapping("/api/meetings/{id}/minutes/announce")
    fun announce(@PathVariable id: Long): MinutesResponse {
        val actor = actor(id)
        minutesService.announce(id, actor)
        return view(id, actor)
    }

    // ---- Helpers ----

    private fun view(meetingId: Long, actor: MinutesActor): MinutesResponse {
        val view = minutesService.view(meetingId, actor) ?: throw NoSuchElementException("No minutes for meeting $meetingId")
        return MinutesResponse.from(view, meetingService.names(MinutesResponse.people(view)))
    }

    // The caller must see the meeting (404 otherwise); what they may do with its minutes follows from
    // their permissions in its society.
    private fun actor(meetingId: Long): MinutesActor {
        val access = accessService.current()
        val societyId = meetingService.get(meetingId).societyId
        meetingService.details(meetingId, viewerOf(access, societyId))
        return MinutesActor(
            userId = access.userId,
            drafts = access.canSocietyWide(Permissions.GOVERNANCE_MINUTES_CREATE, societyId),
            approves = access.canSocietyWide(Permissions.GOVERNANCE_MINUTES_APPROVE, societyId),
        )
    }

    private fun viewerOf(access: Access, societyId: Long) = MeetingViewer(
        userId = access.userId,
        seesGeneral = access.can(Permissions.GOVERNANCE_MEETING_VIEW, societyId),
        seesCommittee = access.canSocietyWide(Permissions.GOVERNANCE_COMMITTEE_VIEW, societyId),
        organises = access.canSocietyWide(Permissions.GOVERNANCE_MEETING_CREATE, societyId),
    )
}
