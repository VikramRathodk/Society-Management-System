package org.devvikram.societymanagement.visitor.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.visitor.dto.LogVisitorRequest
import org.devvikram.societymanagement.visitor.dto.VisitorResponse
import org.devvikram.societymanagement.visitor.model.VisitorEntry
import org.devvikram.societymanagement.visitor.model.VisitorStatus
import org.devvikram.societymanagement.visitor.repository.VisitorSearch
import org.devvikram.societymanagement.visitor.service.LogVisitorCommand
import org.devvikram.societymanagement.visitor.service.VisitorService
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

// Who sees and does what (all within one society):
//  - gate roles (GUARD and above) log visitors and mark them entered/exited;
//  - society-wide roles (GUARD, COMMITTEE and above) see every entry of the society;
//  - flat-tied roles (RESIDENT) see only their own flat's entries, and they approve/reject;
//  - SECRETARY and above may also approve/reject on a flat's behalf, recorded as an override.
@RestController
@RequestMapping("/api/visitors")
class VisitorController(
    private val visitorService: VisitorService,
    private val flatService: FlatService,
    private val accessService: AccessService,
) {

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("VISITOR.ENTRY.CREATE")
    fun log(
        @Valid @ModelAttribute request: LogVisitorRequest,
        @RequestPart("photo", required = false) photo: MultipartFile?,
    ): VisitorResponse {
        val access = accessService.current()
        access.requireIn("VISITOR.ENTRY.CREATE", flatService.societyIdOf(request.flatId!!))
        val id = visitorService.logVisitor(
            LogVisitorCommand(
                flatId = request.flatId,
                visitorName = request.visitorName!!,
                visitorPhone = request.visitorPhone,
                purpose = request.purpose,
                photo = photo?.takeUnless { it.isEmpty }?.bytes,
            ),
            guardId = access.userId,
        )
        return VisitorResponse.from(visitorService.getById(id))
    }

    // The society's gate log, newest first. Residents get their own flat(s) only; flatId narrows it.
    @GetMapping
    @RequiresPermission("VISITOR.ENTRY.VIEW")
    fun list(
        @RequestParam societyId: Long,
        @RequestParam(required = false) status: VisitorStatus?,
        @RequestParam(required = false) flatId: Long?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): List<VisitorResponse> {
        val access = accessService.current()
        access.requireIn("VISITOR.ENTRY.VIEW", societyId)
        val flatIds = if (access.canSocietyWide("VISITOR.ENTRY.VIEW", societyId)) {
            flatId?.let { setOf(it) }
        } else {
            val own = access.flatsWith("VISITOR.ENTRY.VIEW")
            if (flatId != null && flatId !in own) throw AccessDeniedException("You can only see visitors of your own flat")
            flatId?.let { setOf(it) } ?: own
        }
        val search = VisitorSearch(societyId, flatIds, status, page.coerceAtLeast(0), size.coerceIn(1, 100))
        return visitorService.search(search).map(VisitorResponse::from)
    }

    @GetMapping("/{id}")
    @RequiresPermission("VISITOR.ENTRY.VIEW")
    fun getById(@PathVariable id: Long): VisitorResponse = VisitorResponse.from(viewable(id))

    @GetMapping("/{id}/photo")
    @RequiresPermission("VISITOR.ENTRY.VIEW")
    fun photo(@PathVariable id: Long): ResponseEntity<ByteArray> {
        viewable(id)
        val photo = visitorService.getPhoto(id)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(photo.contentType))
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .body(photo.data)
    }

    @PostMapping("/{id}/approve")
    @RequiresPermission("VISITOR.APPROVAL.APPROVE")
    fun approve(@PathVariable id: Long): VisitorResponse = respond(id, VisitorStatus.APPROVED, "VISITOR.APPROVAL.APPROVE")

    @PostMapping("/{id}/reject")
    @RequiresPermission("VISITOR.APPROVAL.REJECT")
    fun reject(@PathVariable id: Long): VisitorResponse = respond(id, VisitorStatus.REJECTED, "VISITOR.APPROVAL.REJECT")

    @PostMapping("/{id}/enter")
    @RequiresPermission("VISITOR.ENTRY.UPDATE")
    fun enter(@PathVariable id: Long): VisitorResponse {
        accessService.current().requireIn("VISITOR.ENTRY.UPDATE", societyOf(id))
        visitorService.markEntered(id)
        return VisitorResponse.from(visitorService.getById(id))
    }

    @PostMapping("/{id}/exit")
    @RequiresPermission("VISITOR.ENTRY.UPDATE")
    fun exit(@PathVariable id: Long): VisitorResponse {
        accessService.current().requireIn("VISITOR.ENTRY.UPDATE", societyOf(id))
        visitorService.markExited(id)
        return VisitorResponse.from(visitorService.getById(id))
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission("VISITOR.ENTRY.DELETE")
    fun delete(@PathVariable id: Long) {
        accessService.current().requireIn("VISITOR.ENTRY.DELETE", societyOf(id))
        visitorService.delete(id)
    }

    // The flat's own residents answer for it. Beyond them, only a holder of VISITOR.APPROVAL.OVERRIDE
    // (SECRETARY and above) may decide on the flat's behalf, and that is recorded as an override.
    // Rank alone is not enough: a committee member holds the approval permission but not the override.
    private fun respond(id: Long, decision: VisitorStatus, permission: String): VisitorResponse {
        val access = accessService.current()
        val entry = visitorService.getById(id)
        val asOverride = when {
            entry.flat.id in access.flatsWith(permission) -> false
            access.canSocietyWide("VISITOR.APPROVAL.OVERRIDE", entry.society.id!!) -> true
            else -> throw AccessDeniedException("Only residents of flat ${entry.flat.flatNumber} can respond to this visitor")
        }
        visitorService.respond(id, decision, access.userId, asOverride)
        return VisitorResponse.from(visitorService.getById(id))
    }

    private fun viewable(id: Long): VisitorEntry {
        val entry = visitorService.getById(id)
        if (!accessService.current().canView(entry)) throw AccessDeniedException("You cannot see this visitor")
        return entry
    }

    private fun Access.canView(entry: VisitorEntry) =
        canOnFlat("VISITOR.ENTRY.VIEW", entry.society.id!!, entry.flat.id!!)

    private fun societyOf(id: Long): Long = visitorService.getById(id).society.id!!
}
