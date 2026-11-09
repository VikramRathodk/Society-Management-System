package org.devvikram.societymanagement.finance.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.finance.dto.AuditEntryResponse
import org.devvikram.societymanagement.finance.dto.BooksResponse
import org.devvikram.societymanagement.finance.dto.ChartSeededResponse
import org.devvikram.societymanagement.finance.dto.CorrectOpeningsRequest
import org.devvikram.societymanagement.finance.dto.OpeningsResponse
import org.devvikram.societymanagement.finance.dto.StartBooksRequest
import org.devvikram.societymanagement.finance.model.AuditEntityType
import org.devvikram.societymanagement.finance.service.BooksService
import org.devvikram.societymanagement.finance.service.ChartService
import org.devvikram.societymanagement.finance.service.FinanceAudit
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneId

// Setting the books up: the default chart, the start date and opening balances; plus the audit log.
// COMMITTEE and above read; SECRETARY and above (or a treasurer role) change.
@RestController
class FinanceBooksController(
    private val booksService: BooksService,
    private val chartService: ChartService,
    private val audit: FinanceAudit,
    private val guard: FinanceGuard,
    @Value("\${app.report.zone}") private val reportZone: ZoneId,
) {
    @GetMapping("/api/societies/{societyId}/finance/books")
    @RequiresPermission(Permissions.FINANCE_ACCOUNT_VIEW)
    fun books(@PathVariable societyId: Long): BooksResponse {
        guard.require(Permissions.FINANCE_ACCOUNT_VIEW, societyId)
        return BooksResponse.from(booksService.state(societyId))
    }

    @PostMapping("/api/societies/{societyId}/finance/chart")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.FINANCE_ACCOUNT_UPDATE)
    fun seedChart(@PathVariable societyId: Long): ChartSeededResponse {
        val access = guard.require(Permissions.FINANCE_ACCOUNT_UPDATE, societyId)
        return ChartSeededResponse(chartService.seedDefault(societyId, access.userId))
    }

    @PostMapping("/api/societies/{societyId}/finance/start")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.FINANCE_ACCOUNT_UPDATE)
    fun start(@PathVariable societyId: Long, @Valid @RequestBody request: StartBooksRequest): BooksResponse {
        val access = guard.require(Permissions.FINANCE_ACCOUNT_UPDATE, societyId)
        booksService.start(societyId, request.toCommand(), access.userId, LocalDate.now(reportZone))
        return BooksResponse.from(booksService.state(societyId))
    }

    @GetMapping("/api/societies/{societyId}/finance/openings")
    @RequiresPermission(Permissions.FINANCE_ACCOUNT_VIEW)
    fun openings(@PathVariable societyId: Long): OpeningsResponse {
        guard.require(Permissions.FINANCE_ACCOUNT_VIEW, societyId)
        return openingsOf(societyId)
    }

    @PutMapping("/api/societies/{societyId}/finance/openings")
    @RequiresPermission(Permissions.FINANCE_ACCOUNT_UPDATE)
    fun correctOpenings(@PathVariable societyId: Long, @Valid @RequestBody request: CorrectOpeningsRequest): OpeningsResponse {
        val access = guard.require(Permissions.FINANCE_ACCOUNT_UPDATE, societyId)
        booksService.correctOpenings(societyId, request.toCommand(), access.userId, LocalDate.now(reportZone))
        return openingsOf(societyId)
    }

    // Newest first; entityType (and entityId) narrow it.
    @GetMapping("/api/societies/{societyId}/finance/audit")
    @RequiresPermission(Permissions.FINANCE_AUDIT_VIEW)
    fun auditLog(
        @PathVariable societyId: Long,
        @RequestParam(required = false) entityType: AuditEntityType?,
        @RequestParam(required = false) entityId: Long?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): List<AuditEntryResponse> {
        guard.require(Permissions.FINANCE_AUDIT_VIEW, societyId)
        val result = audit.search(societyId, entityType, entityId, page.coerceAtLeast(0), size.coerceIn(1, 200))
        return result.entries.map { AuditEntryResponse.from(it, result.names) }
    }

    private fun openingsOf(societyId: Long): OpeningsResponse {
        val view = booksService.openings(societyId)
        val accounts = chartService.accounts(societyId).associateBy { it.id!! }
        return OpeningsResponse.from(view, accounts, booksService.state(societyId).openingsEditable)
    }
}
