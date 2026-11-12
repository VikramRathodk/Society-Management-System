package org.devvikram.societymanagement.finance.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.finance.dto.BankLineResponse
import org.devvikram.societymanagement.finance.dto.CreateStatementRequest
import org.devvikram.societymanagement.finance.dto.ImportResultResponse
import org.devvikram.societymanagement.finance.dto.StatementImportResponse
import org.devvikram.societymanagement.finance.dto.StatementMappingRequest
import org.devvikram.societymanagement.finance.dto.StatementMappingResponse
import org.devvikram.societymanagement.finance.dto.StatementPreviewResponse
import org.devvikram.societymanagement.finance.dto.StatementResponse
import org.devvikram.societymanagement.finance.dto.UpdateStatementRequest
import org.devvikram.societymanagement.finance.model.BankLineState
import org.devvikram.societymanagement.finance.service.BankStatementService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDate
import java.time.ZoneId

// Bank reconciliation: how a bank's CSV reads, statement periods, importing their lines.
//  - COMMITTEE and above see statements and lines (RECONCILIATION.VIEW).
//  - SECRETARY and above (or a treasurer role) map columns, create statements and import (RECONCILIATION.UPDATE).
@RestController
class FinanceReconciliationController(
    private val bankStatementService: BankStatementService,
    private val guard: FinanceGuard,
    @Value("\${app.report.zone}") private val reportZone: ZoneId,
) {
    // ---- Column mapping ----

    @GetMapping("/api/finance/bank-accounts/{accountId}/statement-mapping")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_VIEW)
    fun mapping(@PathVariable accountId: Long): StatementMappingResponse {
        guard.require(Permissions.FINANCE_RECONCILIATION_VIEW, bankStatementService.societyOfAccount(accountId))
        val mapping = bankStatementService.mapping(accountId)
        return StatementMappingResponse.of(accountId, mapping, mapping?.let(BankStatementService::specOf))
    }

    @PutMapping("/api/finance/bank-accounts/{accountId}/statement-mapping")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_UPDATE)
    fun saveMapping(@PathVariable accountId: Long, @Valid @RequestBody request: StatementMappingRequest): StatementMappingResponse {
        val access = guard.require(Permissions.FINANCE_RECONCILIATION_UPDATE, bankStatementService.societyOfAccount(accountId))
        val mapping = bankStatementService.saveMapping(accountId, request.toSpec(), access.userId)
        return StatementMappingResponse.of(accountId, mapping, BankStatementService.specOf(mapping))
    }

    // Reads a CSV without saving anything. Optional part "mapping" (JSON) tries a mapping before saving it.
    @PostMapping("/api/finance/bank-accounts/{accountId}/statement-preview")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_UPDATE)
    fun preview(
        @PathVariable accountId: Long,
        @RequestPart("file") file: MultipartFile,
        @Valid @RequestPart("mapping", required = false) mapping: StatementMappingRequest?,
    ): StatementPreviewResponse {
        guard.require(Permissions.FINANCE_RECONCILIATION_UPDATE, bankStatementService.societyOfAccount(accountId))
        return StatementPreviewResponse.from(bankStatementService.preview(accountId, file.bytes, mapping?.toSpec()))
    }

    // ---- Statements ----

    @GetMapping("/api/societies/{societyId}/finance/statements")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_VIEW)
    fun statements(@PathVariable societyId: Long, @RequestParam(required = false) accountId: Long?): List<StatementResponse> {
        guard.require(Permissions.FINANCE_RECONCILIATION_VIEW, societyId)
        return bankStatementService.list(societyId, accountId).map(StatementResponse::from)
    }

    @PostMapping("/api/finance/bank-accounts/{accountId}/statements")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_UPDATE)
    fun create(@PathVariable accountId: Long, @Valid @RequestBody request: CreateStatementRequest): StatementResponse {
        val access = guard.require(Permissions.FINANCE_RECONCILIATION_UPDATE, bankStatementService.societyOfAccount(accountId))
        val id = bankStatementService.create(accountId, request.toCommand(), access.userId, today())
        return StatementResponse.from(bankStatementService.view(id))
    }

    @GetMapping("/api/finance/statements/{id}")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_VIEW)
    fun statement(@PathVariable id: Long): StatementResponse {
        guard.require(Permissions.FINANCE_RECONCILIATION_VIEW, societyOf(id))
        return StatementResponse.from(bankStatementService.view(id))
    }

    @PatchMapping("/api/finance/statements/{id}")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_UPDATE)
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateStatementRequest): StatementResponse {
        val access = guard.require(Permissions.FINANCE_RECONCILIATION_UPDATE, societyOf(id))
        bankStatementService.update(id, request.toCommand(), access.userId, today())
        return StatementResponse.from(bankStatementService.view(id))
    }

    @DeleteMapping("/api/finance/statements/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_UPDATE)
    fun delete(@PathVariable id: Long) {
        val access = guard.require(Permissions.FINANCE_RECONCILIATION_UPDATE, societyOf(id))
        bankStatementService.delete(id, access.userId)
    }

    @GetMapping("/api/finance/statements/{id}/lines")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_VIEW)
    fun lines(@PathVariable id: Long, @RequestParam(required = false) state: BankLineState?): List<BankLineResponse> {
        guard.require(Permissions.FINANCE_RECONCILIATION_VIEW, societyOf(id))
        return bankStatementService.lines(id, state).map(BankLineResponse::from)
    }

    // ---- Imports ----

    @GetMapping("/api/finance/statements/{id}/imports")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_VIEW)
    fun imports(@PathVariable id: Long): List<StatementImportResponse> {
        guard.require(Permissions.FINANCE_RECONCILIATION_VIEW, societyOf(id))
        return bankStatementService.imports(id).map(StatementImportResponse::from)
    }

    @PostMapping("/api/finance/statements/{id}/imports")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_UPDATE)
    fun import(@PathVariable id: Long, @RequestPart("file") file: MultipartFile): ImportResultResponse {
        val access = guard.require(Permissions.FINANCE_RECONCILIATION_UPDATE, societyOf(id))
        val result = bankStatementService.import(id, file.bytes, file.originalFilename, access.userId)
        return ImportResultResponse.from(result, bankStatementService.view(id))
    }

    @DeleteMapping("/api/finance/statement-imports/{importId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_UPDATE)
    fun deleteImport(@PathVariable importId: Long) {
        val statementImport = bankStatementService.getImport(importId)
        val access = guard.require(Permissions.FINANCE_RECONCILIATION_UPDATE, societyOf(statementImport.statementId))
        bankStatementService.deleteImport(importId, access.userId)
    }

    // The file as uploaded.
    @GetMapping("/api/finance/statement-imports/{importId}/file")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_VIEW)
    fun importFile(@PathVariable importId: Long): ResponseEntity<ByteArray> {
        val statementImport = bankStatementService.getImport(importId)
        guard.require(Permissions.FINANCE_RECONCILIATION_VIEW, societyOf(statementImport.statementId))
        val file = bankStatementService.file(statementImport)
        return ResponseEntity.ok()
            .contentType(MediaType("text", "csv"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(statementImport.filename ?: "statement-$importId.csv").build().toString())
            .body(file.data)
    }

    private fun societyOf(statementId: Long) = bankStatementService.get(statementId).societyId

    private fun today() = LocalDate.now(reportZone)
}
