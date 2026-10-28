package org.devvikram.societymanagement.complaint.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
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
import org.springframework.http.HttpStatus
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

// Who sees and does what (all within one society):
//  - residents raise complaints for their own flat and see their flat's complaints;
//  - only the author edits or withdraws a complaint, and only until it is resolved;
//  - society-wide roles (COMMITTEE and above) see every complaint and move it through its statuses.
@RestController
@RequestMapping("/api/complaints")
class ComplaintController(
    private val complaintService: ComplaintService,
    private val flatService: FlatService,
    private val accessService: AccessService,
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
        val search = ComplaintSearch(societyId, flatIds, status, category, page.coerceAtLeast(0), size.coerceIn(1, 100))
        return complaintService.search(search).map(ComplaintResponse::from)
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.COMPLAINT_TICKET_VIEW)
    fun getById(@PathVariable id: Long): ComplaintResponse {
        val complaint = complaintService.getById(id)
        if (!accessService.current().canOnFlat(Permissions.COMPLAINT_TICKET_VIEW, complaint.society.id!!, complaint.flat.id!!)) {
            throw AccessDeniedException("You cannot see this complaint")
        }
        return ComplaintResponse.from(complaint)
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

    @PostMapping("/{id}/start")
    @RequiresPermission(Permissions.COMPLAINT_RESOLUTION_APPROVE)
    fun start(@PathVariable id: Long): ComplaintResponse =
        move(id, ComplaintStatus.IN_PROGRESS, Permissions.COMPLAINT_RESOLUTION_APPROVE)

    @PostMapping("/{id}/resolve")
    @RequiresPermission(Permissions.COMPLAINT_RESOLUTION_APPROVE)
    fun resolve(@PathVariable id: Long): ComplaintResponse =
        move(id, ComplaintStatus.RESOLVED, Permissions.COMPLAINT_RESOLUTION_APPROVE)

    @PostMapping("/{id}/close")
    @RequiresPermission(Permissions.COMPLAINT_RESOLUTION_APPROVE)
    fun close(@PathVariable id: Long): ComplaintResponse =
        move(id, ComplaintStatus.CLOSED, Permissions.COMPLAINT_RESOLUTION_APPROVE)

    // Rejects the resolution: the complaint goes back to OPEN.
    @PostMapping("/{id}/reopen")
    @RequiresPermission(Permissions.COMPLAINT_RESOLUTION_REJECT)
    fun reopen(@PathVariable id: Long): ComplaintResponse =
        move(id, ComplaintStatus.OPEN, Permissions.COMPLAINT_RESOLUTION_REJECT)

    // Managing complaints is a society-wide act; a flat-tied role never moves them.
    private fun move(id: Long, to: ComplaintStatus, permission: String): ComplaintResponse {
        val access = accessService.current()
        if (!access.canSocietyWide(permission, complaintService.getById(id).society.id!!)) {
            throw AccessDeniedException("Missing permission: $permission for this society")
        }
        complaintService.changeStatus(id, to, access.userId)
        return ComplaintResponse.from(complaintService.getById(id))
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
