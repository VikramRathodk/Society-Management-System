package org.devvikram.societymanagement.finance.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.common.export.Csv
import org.devvikram.societymanagement.finance.dto.AutoMatchResponse
import org.devvikram.societymanagement.finance.dto.BankLineDetailResponse
import org.devvikram.societymanagement.finance.dto.BookEntryResponse
import org.devvikram.societymanagement.finance.dto.BrsResponse
import org.devvikram.societymanagement.finance.dto.ReopenStatementRequest
import org.devvikram.societymanagement.finance.dto.ExcludeLineRequest
import org.devvikram.societymanagement.finance.dto.LineVoucherRequest
import org.devvikram.societymanagement.finance.dto.LineVoucherResponse
import org.devvikram.societymanagement.finance.dto.MatchRequest
import org.devvikram.societymanagement.finance.dto.PairRequest
import org.devvikram.societymanagement.finance.dto.SuggestionsResponse
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
import org.devvikram.societymanagement.finance.service.ReconciliationService
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
    private val reconciliationService: ReconciliationService,
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
    fun lines(@PathVariable id: Long, @RequestParam(required = false) state: BankLineState?): List<BankLineDetailResponse> {
        guard.require(Permissions.FINANCE_RECONCILIATION_VIEW, societyOf(id))
        return reconciliationService.lines(id, state).map(BankLineDetailResponse::from)
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
        val (result, auto) = reconciliationService.importAndMatch(id, file.bytes, file.originalFilename, access.userId)
        return ImportResultResponse.from(result, auto, bankStatementService.view(id))
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

    // ---- Matching ----

    // cleared=false: entries still waiting to clear up to the statement's end (carried over ones too);
    // cleared=true: the entries this statement cleared.
    @GetMapping("/api/finance/statements/{id}/book-entries")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_VIEW)
    fun bookEntries(@PathVariable id: Long, @RequestParam(defaultValue = "false") cleared: Boolean): List<BookEntryResponse> {
        guard.require(Permissions.FINANCE_RECONCILIATION_VIEW, societyOf(id))
        return reconciliationService.bookEntries(id, cleared).map(BookEntryResponse::from)
    }

    @GetMapping("/api/finance/statements/{id}/lines/{lineId}/suggestions")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_VIEW)
    fun suggestions(@PathVariable id: Long, @PathVariable lineId: Long): SuggestionsResponse {
        guard.require(Permissions.FINANCE_RECONCILIATION_VIEW, societyOf(id))
        return SuggestionsResponse.from(reconciliationService.suggestions(id, lineId))
    }

    @PostMapping("/api/finance/statements/{id}/auto-match")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_UPDATE)
    fun autoMatch(@PathVariable id: Long): AutoMatchResponse {
        val access = guard.require(Permissions.FINANCE_RECONCILIATION_UPDATE, societyOf(id))
        val result = reconciliationService.autoMatch(id, access.userId)
        return AutoMatchResponse(result.matched, result.paired, StatementResponse.from(bankStatementService.view(id)))
    }

    @PostMapping("/api/finance/statements/{id}/lines/{lineId}/match")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_UPDATE)
    fun match(@PathVariable id: Long, @PathVariable lineId: Long, @Valid @RequestBody request: MatchRequest): BankLineDetailResponse {
        val access = guard.require(Permissions.FINANCE_RECONCILIATION_UPDATE, societyOf(id))
        reconciliationService.match(id, lineId, request.bookLineIds!!, access.userId)
        return line(id, lineId)
    }

    @DeleteMapping("/api/finance/statements/{id}/lines/{lineId}/match")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_UPDATE)
    fun unmatch(@PathVariable id: Long, @PathVariable lineId: Long): BankLineDetailResponse {
        val access = guard.require(Permissions.FINANCE_RECONCILIATION_UPDATE, societyOf(id))
        reconciliationService.unmatch(id, lineId, access.userId)
        return line(id, lineId)
    }

    @PostMapping("/api/finance/statements/{id}/lines/{lineId}/exclude")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_UPDATE)
    fun exclude(@PathVariable id: Long, @PathVariable lineId: Long, @Valid @RequestBody request: ExcludeLineRequest): BankLineDetailResponse {
        val access = guard.require(Permissions.FINANCE_RECONCILIATION_UPDATE, societyOf(id))
        reconciliationService.exclude(id, lineId, request.reason!!, access.userId)
        return line(id, lineId)
    }

    @PostMapping("/api/finance/statements/{id}/lines/{lineId}/include")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_UPDATE)
    fun include(@PathVariable id: Long, @PathVariable lineId: Long): BankLineDetailResponse {
        val access = guard.require(Permissions.FINANCE_RECONCILIATION_UPDATE, societyOf(id))
        reconciliationService.include(id, lineId, access.userId)
        return line(id, lineId)
    }

    // Records the missing entry from a bank line; needs VOUCHER.CREATE as well.
    @PostMapping("/api/finance/statements/{id}/lines/{lineId}/voucher")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_UPDATE)
    fun recordVoucher(@PathVariable id: Long, @PathVariable lineId: Long, @Valid @RequestBody request: LineVoucherRequest): LineVoucherResponse {
        val societyId = societyOf(id)
        val access = guard.require(Permissions.FINANCE_RECONCILIATION_UPDATE, societyId)
        guard.require(Permissions.FINANCE_VOUCHER_CREATE, societyId)
        val voucherId = reconciliationService.recordVoucher(id, lineId, request.toCommand(), access.userId, today())
        val line = line(id, lineId)
        return LineVoucherResponse(voucherId, line.voucherNumber!!, line.voucherStatus!!, line)
    }

    @PostMapping("/api/finance/statements/{id}/pairs")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_UPDATE)
    fun pair(@PathVariable id: Long, @Valid @RequestBody request: PairRequest) {
        val access = guard.require(Permissions.FINANCE_RECONCILIATION_UPDATE, societyOf(id))
        reconciliationService.pair(id, request.firstLineId!!, request.secondLineId!!, access.userId)
    }

    @DeleteMapping("/api/finance/statements/{id}/pairs/{bookLineId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_UPDATE)
    fun unpair(@PathVariable id: Long, @PathVariable bookLineId: Long) {
        val access = guard.require(Permissions.FINANCE_RECONCILIATION_UPDATE, societyOf(id))
        reconciliationService.unpair(id, bookLineId, access.userId)
    }

    // ---- Bank reconciliation statement, complete, reopen ----

    @GetMapping("/api/finance/statements/{id}/brs")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_VIEW)
    fun brs(@PathVariable id: Long): BrsResponse {
        guard.require(Permissions.FINANCE_RECONCILIATION_VIEW, societyOf(id))
        return BrsResponse.from(reconciliationService.brs(id))
    }

    @GetMapping("/api/finance/statements/{id}/brs.csv")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_VIEW)
    fun brsCsv(@PathVariable id: Long): ResponseEntity<ByteArray> {
        guard.require(Permissions.FINANCE_RECONCILIATION_VIEW, societyOf(id))
        val brs = reconciliationService.brs(id)
        val statement = brs.view.statement
        val rows = buildList<List<Any?>> {
            add(listOf("Balance in the books", statement.periodTo, null, null, brs.bookBalance))
            brs.receipts.forEach { add(listOf("Less: receipt not yet in the bank", it.date, it.number, it.payee ?: it.narration, it.net)) }
            add(listOf("Total receipts not yet in the bank", null, null, null, brs.unclearedReceipts))
            brs.payments.forEach { add(listOf("Add: payment not yet out of the bank", it.date, it.number, it.payee ?: it.narration, it.net.negate())) }
            add(listOf("Total payments not yet out of the bank", null, null, null, brs.unclearedPayments))
            brs.excluded.forEach { add(listOf("Bank line set aside", it.txnDate, null, it.description + (it.excludeReason?.let { r -> " ($r)" } ?: ""), it.net)) }
            add(listOf("Balance the books explain", null, null, null, brs.explainedBalance))
            add(listOf("Balance as per bank statement", statement.periodTo, null, null, statement.closingBalance))
            add(listOf("Difference", null, null, null, brs.difference))
        }
        return Csv.download(
            "brs-${brs.view.accountName.replace(Regex("[^A-Za-z0-9]+"), "-")}-${statement.periodTo}.csv",
            Csv.write(listOf("Item", "Date", "Voucher", "Details", "Amount"), rows),
        )
    }

    @PostMapping("/api/finance/statements/{id}/complete")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_COMPLETE)
    fun complete(@PathVariable id: Long): BrsResponse {
        val access = guard.require(Permissions.FINANCE_RECONCILIATION_COMPLETE, societyOf(id))
        reconciliationService.complete(id, access.userId)
        return BrsResponse.from(reconciliationService.brs(id))
    }

    @PostMapping("/api/finance/statements/{id}/reopen")
    @RequiresPermission(Permissions.FINANCE_RECONCILIATION_COMPLETE)
    fun reopen(@PathVariable id: Long, @Valid @RequestBody request: ReopenStatementRequest): StatementResponse {
        val access = guard.require(Permissions.FINANCE_RECONCILIATION_COMPLETE, societyOf(id))
        reconciliationService.reopen(id, request.reason!!, access.userId)
        return StatementResponse.from(bankStatementService.view(id))
    }

    private fun line(statementId: Long, lineId: Long) =
        BankLineDetailResponse.from(reconciliationService.lines(statementId, null).first { it.line.id == lineId })

    private fun societyOf(statementId: Long) = bankStatementService.get(statementId).societyId

    private fun today() = LocalDate.now(reportZone)
}
