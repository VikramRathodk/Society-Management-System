package org.devvikram.societymanagement.complaint.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.common.export.Csv
import org.devvikram.societymanagement.common.export.ReportRange
import org.devvikram.societymanagement.complaint.dto.ChangeComplaintStatusRequest
import org.devvikram.societymanagement.complaint.dto.ComplaintActivityResponse
import org.devvikram.societymanagement.complaint.dto.ComplaintResponse
import org.devvikram.societymanagement.complaint.dto.RaiseComplaintRequest
import org.devvikram.societymanagement.complaint.dto.UpdateComplaintRequest
import org.devvikram.societymanagement.complaint.model.Complaint
import org.devvikram.societymanagement.complaint.model.ComplaintCategory
import org.devvikram.societymanagement.complaint.model.ComplaintStatus
import org.devvikram.societymanagement.complaint.repository.ComplaintSearch
import org.devvikram.societymanagement.complaint.service.ComplaintService
import org.devvikram.societymanagement.complaint.service.RaiseComplaintCommand
import org.devvikram.societymanagement.complaint.service.UpdateComplaintCommand
import org.devvikram.societymanagement.society.service.FlatService
import org.springframework.beans.factory.annotation.Value
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneId

// Who sees and does what (all within one society):
//  - residents raise complaints for their own flat and see their flat's complaints;
//  - only the author edits or withdraws a complaint, and only until it is resolved;
//  - society-wide roles (COMMITTEE and above) see every complaint, move it through its statuses (with an
//    optional note, kept in its timeline) and export the society's complaints.
@RestController
@RequestMapping("/api/complaints")
class ComplaintController(
    private val complaintService: ComplaintService,
    private val flatService: FlatService,
    private val accessService: AccessService,
    @Value("\${app.report.zone}") private val reportZone: ZoneId,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.COMPLAINT_TICKET_CREATE)
    fun raise(@Valid @RequestBody request: RaiseComplaintRequest): ComplaintResponse {
        val access = accessService.current()
        val flatId = request.flatId!!
        if (!access.canOnFlat(Permissions.COMPLAINT_TICKET_CREATE, flatService.societyIdOf(flatId), flatId)) {
            throw AccessDeniedException("You can only raise complaints for your own flat")
        }
        val id = complaintService.raise(
            RaiseComplaintCommand(flatId, request.category!!, request.description),
            access.userId,
        )
        return ComplaintResponse.from(complaintService.getById(id))
    }

    // The society's complaints, newest first. Residents get their own flat(s) only; flatId narrows it.
    @GetMapping
    @RequiresPermission(Permissions.COMPLAINT_TICKET_VIEW)
    fun list(
        @RequestParam societyId: Long,
        @RequestParam(required = false) status: ComplaintStatus?,
        @RequestParam(required = false) category: ComplaintCategory?,
        @RequestParam(required = false) flatId: Long?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): List<ComplaintResponse> {
        val access = accessService.current()
        access.requireIn(Permissions.COMPLAINT_TICKET_VIEW, societyId)
        val flatIds = if (access.canSocietyWide(Permissions.COMPLAINT_TICKET_VIEW, societyId)) {
            flatId?.let { setOf(it) }
        } else {
            val own = access.flatsWith(Permissions.COMPLAINT_TICKET_VIEW)
            if (flatId != null && flatId !in own) throw AccessDeniedException("You can only see complaints of your own flat")
            flatId?.let { setOf(it) } ?: own
        }
        val search = ComplaintSearch(
            societyId = societyId,
            flatIds = flatIds,
            status = status,
            category = category,
            page = page.coerceAtLeast(0),
            size = size.coerceIn(1, 100),
        )
        return complaintService.search(search).map(ComplaintResponse::from)
    }

    // The society's complaints raised in a date range (inclusive, local days) as a CSV download.
    @GetMapping("/export")
    @RequiresPermission(Permissions.COMPLAINT_TICKET_EXPORT)
    fun export(
        @RequestParam societyId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ResponseEntity<ByteArray> {
        if (!accessService.current().canSocietyWide(Permissions.COMPLAINT_TICKET_EXPORT, societyId)) {
            throw AccessDeniedException("Missing permission: ${Permissions.COMPLAINT_TICKET_EXPORT} for this society")
        }
        val range = ReportRange.of(from, to, reportZone)
        val complaints = complaintService.search(
            ComplaintSearch(
                societyId = societyId,
                createdFrom = range.start,
                createdTo = range.endExclusive,
                size = ReportRange.MAX_ROWS + 1,
            )
        )
        ReportRange.requireWithinLimit(complaints.size)
        val csv = Csv.write(
            listOf("id", "wing", "flat", "category", "description", "status", "raised_by", "raised_at", "updated_by", "updated_at"),
            complaints.map {
                listOf(
                    it.id, it.flat.wing.name, it.flat.flatNumber, it.category, it.description, it.status,
                    it.raisedBy.name, range.format(it.createdAt), it.updatedBy?.name, range.format(it.updatedAt),
                )
            },
        )
        return Csv.download("complaints-$societyId-$from-to-$to.csv", csv)
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.COMPLAINT_TICKET_VIEW)
    fun getById(@PathVariable id: Long): ComplaintResponse = ComplaintResponse.from(viewable(id))

    // The complaint's timeline, oldest first: raised, then each status change with its note.
    @GetMapping("/{id}/activity")
    @RequiresPermission(Permissions.COMPLAINT_TICKET_VIEW)
    fun activity(@PathVariable id: Long): List<ComplaintActivityResponse> {
        viewable(id)
        return complaintService.activityOf(id).map(ComplaintActivityResponse::from)
    }

    @PatchMapping("/{id}")
    @RequiresPermission(Permissions.COMPLAINT_TICKET_UPDATE)
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateComplaintRequest): ComplaintResponse {
        ownComplaint(id, Permissions.COMPLAINT_TICKET_UPDATE, "You can only edit your own complaints")
        complaintService.update(
            id,
            UpdateComplaintCommand(category = request.category, description = request.description),
            accessService.current().userId,
        )
        return ComplaintResponse.from(complaintService.getById(id))
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.COMPLAINT_TICKET_DELETE)
    fun withdraw(@PathVariable id: Long) {
        ownComplaint(id, Permissions.COMPLAINT_TICKET_DELETE, "You can only withdraw your own complaints")
        complaintService.withdraw(id)
    }

    // Each status change takes an optional {"note": "..."} body, kept in the complaint's timeline.
    @PostMapping("/{id}/start")
    @RequiresPermission(Permissions.COMPLAINT_RESOLUTION_APPROVE)
    fun start(@PathVariable id: Long, @Valid @RequestBody(required = false) request: ChangeComplaintStatusRequest?) =
        move(id, ComplaintStatus.IN_PROGRESS, Permissions.COMPLAINT_RESOLUTION_APPROVE, request)

    @PostMapping("/{id}/resolve")
    @RequiresPermission(Permissions.COMPLAINT_RESOLUTION_APPROVE)
    fun resolve(@PathVariable id: Long, @Valid @RequestBody(required = false) request: ChangeComplaintStatusRequest?) =
        move(id, ComplaintStatus.RESOLVED, Permissions.COMPLAINT_RESOLUTION_APPROVE, request)

    @PostMapping("/{id}/close")
    @RequiresPermission(Permissions.COMPLAINT_RESOLUTION_APPROVE)
    fun close(@PathVariable id: Long, @Valid @RequestBody(required = false) request: ChangeComplaintStatusRequest?) =
        move(id, ComplaintStatus.CLOSED, Permissions.COMPLAINT_RESOLUTION_APPROVE, request)

    // Rejects the resolution: the complaint goes back to OPEN.
    @PostMapping("/{id}/reopen")
    @RequiresPermission(Permissions.COMPLAINT_RESOLUTION_REJECT)
    fun reopen(@PathVariable id: Long, @Valid @RequestBody(required = false) request: ChangeComplaintStatusRequest?) =
        move(id, ComplaintStatus.OPEN, Permissions.COMPLAINT_RESOLUTION_REJECT, request)

    // Managing complaints is a society-wide act; a flat-tied role never moves them.
    private fun move(id: Long, to: ComplaintStatus, permission: String, request: ChangeComplaintStatusRequest?): ComplaintResponse {
        val access = accessService.current()
        if (!access.canSocietyWide(permission, complaintService.getById(id).society.id!!)) {
            throw AccessDeniedException("Missing permission: $permission for this society")
        }
        complaintService.changeStatus(id, to, access.userId, request?.note)
        return ComplaintResponse.from(complaintService.getById(id))
    }

    private fun viewable(id: Long): Complaint {
        val complaint = complaintService.getById(id)
        if (!accessService.current().canOnFlat(Permissions.COMPLAINT_TICKET_VIEW, complaint.society.id!!, complaint.flat.id!!)) {
            throw AccessDeniedException("You cannot see this complaint")
        }
        return complaint
    }

    // The permission in the complaint's society, and authorship — no role acts on someone else's complaint.
    private fun ownComplaint(id: Long, permission: String, notOwnMessage: String): Complaint {
        val access = accessService.current()
        val complaint = complaintService.getById(id)
        access.requireIn(permission, complaint.society.id!!)
        if (complaint.raisedBy.id != access.userId) throw AccessDeniedException(notOwnMessage)
        return complaint
    }
}
