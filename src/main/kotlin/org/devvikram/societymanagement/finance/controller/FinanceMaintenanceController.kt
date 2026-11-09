package org.devvikram.societymanagement.finance.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.finance.dto.ChargeHeadMappingResponse
import org.devvikram.societymanagement.finance.dto.ChargeHeadMappingsRequest
import org.devvikram.societymanagement.finance.dto.MaintenanceCheckResponse
import org.devvikram.societymanagement.finance.service.ChargeHeadMappingService
import org.devvikram.societymanagement.finance.service.MaintenanceCheckService
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneId

// How maintenance reaches the books: the ledger each charge head credits, and the check that the member
// sub-ledgers agree with maintenance.
@RestController
class FinanceMaintenanceController(
    private val mappingService: ChargeHeadMappingService,
    private val checkService: MaintenanceCheckService,
    private val guard: FinanceGuard,
    @Value("\${app.report.zone}") private val reportZone: ZoneId,
) {
    @GetMapping("/api/societies/{societyId}/finance/charge-head-accounts")
    @RequiresPermission(Permissions.FINANCE_ACCOUNT_VIEW)
    fun mappings(@PathVariable societyId: Long): List<ChargeHeadMappingResponse> {
        guard.require(Permissions.FINANCE_ACCOUNT_VIEW, societyId)
        return mappingService.mappings(societyId).map(ChargeHeadMappingResponse::from)
    }

    @PutMapping("/api/societies/{societyId}/finance/charge-head-accounts")
    @RequiresPermission(Permissions.FINANCE_ACCOUNT_UPDATE)
    fun updateMappings(@PathVariable societyId: Long, @Valid @RequestBody request: ChargeHeadMappingsRequest): List<ChargeHeadMappingResponse> {
        val access = guard.require(Permissions.FINANCE_ACCOUNT_UPDATE, societyId)
        val duplicate = request.mappings.groupBy { it.chargeHeadId }.filterValues { it.size > 1 }.keys.firstOrNull()
        require(duplicate == null) { "Charge head $duplicate is listed more than once" }
        mappingService.update(societyId, request.mappings.associate { it.chargeHeadId!! to it.accountId }, access.userId)
        return mappingService.mappings(societyId).map(ChargeHeadMappingResponse::from)
    }

    @GetMapping("/api/societies/{societyId}/finance/maintenance-check")
    @RequiresPermission(Permissions.FINANCE_REPORT_VIEW)
    fun check(@PathVariable societyId: Long): MaintenanceCheckResponse {
        guard.require(Permissions.FINANCE_REPORT_VIEW, societyId)
        return MaintenanceCheckResponse.from(checkService.check(societyId, LocalDate.now(reportZone)))
    }
}
