package org.devvikram.societymanagement.maintenance.controller

import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.common.export.Csv
import org.devvikram.societymanagement.maintenance.dto.FlatDuesResponse
import org.devvikram.societymanagement.maintenance.dto.ReminderResponse
import org.devvikram.societymanagement.maintenance.dto.RemindRequest
import org.devvikram.societymanagement.maintenance.service.MaintenanceAudience
import org.devvikram.societymanagement.maintenance.service.MaintenanceReportService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneId

// Dues per flat and defaulters. Residents see their own flat's row ("my dues"); society-wide viewers see
// every flat that owes something; COMMITTEE and above export; SECRETARY and above send reminders.
@RestController
class MaintenanceReportController(
    private val reportService: MaintenanceReportService,
    private val accessService: AccessService,
    private val audience: MaintenanceAudience,
    @Value("\${app.report.zone}") private val reportZone: ZoneId,
) {

    // overdueOnly=true lists the defaulters.
    @GetMapping("/api/societies/{societyId}/maintenance/dues")
    @RequiresPermission(Permissions.MAINTENANCE_BILL_VIEW)
    fun dues(
        @PathVariable societyId: Long,
        @RequestParam(defaultValue = "false") overdueOnly: Boolean,
    ): List<FlatDuesResponse> {
        val access = accessService.current()
        access.requireIn(Permissions.MAINTENANCE_BILL_VIEW, societyId)
        val flatIds = if (access.canSocietyWide(Permissions.MAINTENANCE_BILL_VIEW, societyId)) {
            null
        } else {
            audience.ownFlats(access, Permissions.MAINTENANCE_BILL_VIEW, societyId)
        }
        return reportService.dues(societyId, today(), flatIds, overdueOnly).map(FlatDuesResponse::from)
    }

    @GetMapping("/api/societies/{societyId}/maintenance/dues/export")
    @RequiresPermission(Permissions.MAINTENANCE_BILL_EXPORT)
    fun exportDues(
        @PathVariable societyId: Long,
        @RequestParam(defaultValue = "false") overdueOnly: Boolean,
    ): ResponseEntity<ByteArray> {
        requireSocietyWide(Permissions.MAINTENANCE_BILL_EXPORT, societyId)
        val today = today()
        val csv = Csv.write(
            listOf("wing", "flat", "open_bills", "outstanding", "overdue", "oldest_due_date", "credit"),
            reportService.dues(societyId, today, overdueOnly = overdueOnly).map {
                listOf(it.wingName, it.flatNumber, it.openBills, it.outstanding.setScale(2), it.overdue.setScale(2), it.oldestDueDate, it.credit)
            },
        )
        return Csv.download("maintenance-dues-$societyId-$today.csv", csv)
    }

    // Pushes a reminder to the residents of every defaulting flat, or only of the listed ones.
    @PostMapping("/api/societies/{societyId}/maintenance/dues/remind")
    @RequiresPermission(Permissions.MAINTENANCE_BILL_UPDATE)
    fun remind(@PathVariable societyId: Long, @RequestBody(required = false) request: RemindRequest?): ReminderResponse {
        requireSocietyWide(Permissions.MAINTENANCE_BILL_UPDATE, societyId)
        return ReminderResponse.from(reportService.remindDefaulters(societyId, today(), request?.flatIds))
    }

    private fun today(): LocalDate = LocalDate.now(reportZone)

    private fun requireSocietyWide(permission: String, societyId: Long): Access = accessService.current().also {
        if (!it.canSocietyWide(permission, societyId)) {
            throw AccessDeniedException("Missing permission: $permission for this society")
        }
    }
}
