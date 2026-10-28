package org.devvikram.societymanagement.visitor.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.common.export.Csv
import org.devvikram.societymanagement.common.export.ReportRange
import org.devvikram.societymanagement.society.service.FlatService
import org.devvikram.societymanagement.visitor.dto.LogVisitorRequest
import org.devvikram.societymanagement.visitor.dto.VisitorResponse
import org.devvikram.societymanagement.visitor.model.VisitorEntry
import org.devvikram.societymanagement.visitor.model.VisitorStatus
import org.devvikram.societymanagement.visitor.repository.VisitorSearch
import org.devvikram.societymanagement.visitor.service.LogVisitorCommand
import org.devvikram.societymanagement.visitor.service.VisitorService
import org.springframework.beans.factory.annotation.Value
import org.springframework.format.annotation.DateTimeFormat
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
import java.time.LocalDate
import java.time.ZoneId

// Who sees and does what (all within one society):
//  - gate roles (GUARD and above) log visitors and mark them entered/exited;
//  - COMMITTEE and above (VISITOR.HISTORY.VIEW) see every entry of the society and export it;
//  - guards see what they logged plus every still-active entry, so the next shift can take over;
//  - flat-tied roles (RESIDENT) see only their own flat's entries, and they approve/reject;
//  - SECRETARY and above may also approve/reject on a flat's behalf, recorded as an override.
@RestController
@RequestMapping("/api/visitors")
class VisitorController(
    private val visitorService: VisitorService,
    private val flatService: FlatService,
    private val accessService: AccessService,
    @Value("\${app.report.zone}") private val reportZone: ZoneId,
) {

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.VISITOR_ENTRY_CREATE)
    fun log(
        @Valid @ModelAttribute request: LogVisitorRequest,
        @RequestPart("photo", required = false) photo: MultipartFile?,
    ): VisitorResponse {
        val access = accessService.current()
        access.requireIn(Permissions.VISITOR_ENTRY_CREATE, flatService.societyIdOf(request.flatId!!))
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
    @RequiresPermission(Permissions.VISITOR_ENTRY_VIEW)
    fun list(
        @RequestParam societyId: Long,
        @RequestParam(required = false) status: VisitorStatus?,
        @RequestParam(required = false) flatId: Long?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): List<VisitorResponse> {
        val access = accessService.current()
        access.requireIn(Permissions.VISITOR_ENTRY_VIEW, societyId)
        val societyWide = access.canSocietyWide(Permissions.VISITOR_ENTRY_VIEW, societyId)
        val flatIds = if (societyWide) {
            flatId?.let { setOf(it) }
        } else {
            val own = access.flatsWith(Permissions.VISITOR_ENTRY_VIEW)
            if (flatId != null && flatId !in own) throw AccessDeniedException("You can only see visitors of your own flat")
            flatId?.let { setOf(it) } ?: own
        }
        val guardView = societyWide && !access.canSocietyWide(Permissions.VISITOR_HISTORY_VIEW, societyId)
        val search = VisitorSearch(
            societyId = societyId,
            flatIds = flatIds,
            status = status,
            loggedByOrActive = if (guardView) access.userId else null,
            page = page.coerceAtLeast(0),
            size = size.coerceIn(1, 100),
        )
        return visitorService.search(search).map(VisitorResponse::from)
    }

    // The society's gate log for a date range (inclusive, local days) as a CSV download.
    @GetMapping("/export")
    @RequiresPermission(Permissions.VISITOR_ENTRY_EXPORT)
    fun export(
        @RequestParam societyId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ResponseEntity<ByteArray> {
        if (!accessService.current().canSocietyWide(Permissions.VISITOR_ENTRY_EXPORT, societyId)) {
            throw AccessDeniedException("Missing permission: ${Permissions.VISITOR_ENTRY_EXPORT} for this society")
        }
        val range = ReportRange.of(from, to, reportZone)
        val entries = visitorService.search(
            VisitorSearch(
                societyId = societyId,
                loggedFrom = range.start,
                loggedTo = range.endExclusive,
                size = ReportRange.MAX_ROWS + 1,
            )
        )
        ReportRange.requireWithinLimit(entries.size)
        val csv = Csv.write(
            listOf(
                "id", "wing", "flat", "visitor", "phone", "purpose", "status", "logged_by", "logged_at",
                "responded_by", "override", "responded_at", "entered_at", "exited_at",
            ),
            entries.map {
                listOf(
                    it.id, it.flat.wing.name, it.flat.flatNumber, it.visitorName, it.visitorPhone, it.purpose,
                    it.status, it.loggedBy.name, range.format(it.loggedAt), it.respondedBy?.name,
                    it.respondedAsOverride, range.format(it.respondedAt), range.format(it.enteredAt),
                    range.format(it.exitedAt),
                )
            },
        )
        return Csv.download("visitors-$societyId-$from-to-$to.csv", csv)
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.VISITOR_ENTRY_VIEW)
    fun getById(@PathVariable id: Long): VisitorResponse = VisitorResponse.from(viewable(id))

    @GetMapping("/{id}/photo")
    @RequiresPermission(Permissions.VISITOR_ENTRY_VIEW)
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
    @RequiresPermission(Permissions.VISITOR_APPROVAL_APPROVE)
    fun approve(@PathVariable id: Long): VisitorResponse = respond(id, VisitorStatus.APPROVED, Permissions.VISITOR_APPROVAL_APPROVE)

    @PostMapping("/{id}/reject")
    @RequiresPermission(Permissions.VISITOR_APPROVAL_REJECT)
    fun reject(@PathVariable id: Long): VisitorResponse = respond(id, VisitorStatus.REJECTED, Permissions.VISITOR_APPROVAL_REJECT)

    @PostMapping("/{id}/enter")
    @RequiresPermission(Permissions.VISITOR_ENTRY_UPDATE)
    fun enter(@PathVariable id: Long): VisitorResponse {
        accessService.current().requireIn(Permissions.VISITOR_ENTRY_UPDATE, societyOf(id))
        visitorService.markEntered(id)
        return VisitorResponse.from(visitorService.getById(id))
    }

    @PostMapping("/{id}/exit")
    @RequiresPermission(Permissions.VISITOR_ENTRY_UPDATE)
    fun exit(@PathVariable id: Long): VisitorResponse {
        accessService.current().requireIn(Permissions.VISITOR_ENTRY_UPDATE, societyOf(id))
        visitorService.markExited(id)
        return VisitorResponse.from(visitorService.getById(id))
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.VISITOR_ENTRY_DELETE)
    fun delete(@PathVariable id: Long) {
        accessService.current().requireIn(Permissions.VISITOR_ENTRY_DELETE, societyOf(id))
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
            access.canSocietyWide(Permissions.VISITOR_APPROVAL_OVERRIDE, entry.society.id!!) -> true
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

    // Flat-tied roles: their own flat's entries. Society-wide roles: everything with VISITOR.HISTORY.VIEW,
    // otherwise (guards) what they logged plus anything still active.
    private fun Access.canView(entry: VisitorEntry): Boolean {
        val societyId = entry.society.id!!
        if (canSocietyWide(Permissions.VISITOR_ENTRY_VIEW, societyId)) {
            return canSocietyWide(Permissions.VISITOR_HISTORY_VIEW, societyId) ||
                entry.loggedBy.id == userId || entry.status in VisitorStatus.ACTIVE
        }
        return entry.flat.id in flatsWith(Permissions.VISITOR_ENTRY_VIEW)
    }

    private fun societyOf(id: Long): Long = visitorService.getById(id).society.id!!
}
