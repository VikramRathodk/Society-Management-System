package org.devvikram.societymanagement.finance.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.finance.dto.BookDefaultsRequest
import org.devvikram.societymanagement.finance.dto.BooksResponse
import org.devvikram.societymanagement.finance.dto.CashAndBankResponse
import org.devvikram.societymanagement.finance.dto.ContraRequest
import org.devvikram.societymanagement.finance.dto.DocumentResponse
import org.devvikram.societymanagement.finance.dto.JournalRequest
import org.devvikram.societymanagement.finance.dto.LockRequest
import org.devvikram.societymanagement.finance.dto.PaymentRequest
import org.devvikram.societymanagement.finance.dto.ReasonRequest
import org.devvikram.societymanagement.finance.dto.ReceiptRequest
import org.devvikram.societymanagement.finance.dto.VoucherResponse
import org.devvikram.societymanagement.finance.model.DocumentType
import org.devvikram.societymanagement.finance.model.Voucher
import org.devvikram.societymanagement.finance.model.VoucherStatus
import org.devvikram.societymanagement.finance.model.VoucherType
import org.devvikram.societymanagement.finance.repository.VoucherSearch
import org.devvikram.societymanagement.finance.service.BooksService
import org.devvikram.societymanagement.finance.service.DocumentService
import org.devvikram.societymanagement.finance.service.LedgerService
import org.devvikram.societymanagement.finance.service.VoucherContextService
import org.devvikram.societymanagement.finance.service.VoucherService
import org.springframework.beans.factory.annotation.Value
import org.springframework.format.annotation.DateTimeFormat
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDate
import java.time.ZoneId

// Vouchers entered by hand, their approval, voiding and documents; the lock date and default accounts;
// the day book and the cash & bank summary.
//  - SECRETARY and above (or a treasurer role) record receipts, payments and transfers (VOUCHER.CREATE),
//    journals (JOURNAL.CREATE), and void (VOUCHER.UPDATE).
//  - COMMITTEE and above read, and approve or reject what waits (VOUCHER.APPROVE), never their own.
//  - Only a waiting voucher's maker edits or withdraws it.
@RestController
class FinanceVoucherController(
    private val voucherService: VoucherService,
    private val contextService: VoucherContextService,
    private val documentService: DocumentService,
    private val booksService: BooksService,
    private val ledgerService: LedgerService,
    private val guard: FinanceGuard,
    @Value("\${app.report.zone}") private val reportZone: ZoneId,
) {
    private fun today() = LocalDate.now(reportZone)

    // ---- Create ----

    @PostMapping("/api/societies/{societyId}/finance/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.FINANCE_VOUCHER_CREATE)
    fun receipt(@PathVariable societyId: Long, @Valid @RequestBody request: ReceiptRequest): VoucherResponse {
        val access = guard.require(Permissions.FINANCE_VOUCHER_CREATE, societyId)
        return view(voucherService.recordReceipt(societyId, request.toCommand(), access.userId, today()))
    }

    @PostMapping("/api/societies/{societyId}/finance/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.FINANCE_VOUCHER_CREATE)
    fun payment(@PathVariable societyId: Long, @Valid @RequestBody request: PaymentRequest): VoucherResponse {
        val access = guard.require(Permissions.FINANCE_VOUCHER_CREATE, societyId)
        return view(voucherService.recordPayment(societyId, request.toCommand(), access.userId, today()))
    }

    @PostMapping("/api/societies/{societyId}/finance/contras")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.FINANCE_VOUCHER_CREATE)
    fun contra(@PathVariable societyId: Long, @Valid @RequestBody request: ContraRequest): VoucherResponse {
        val access = guard.require(Permissions.FINANCE_VOUCHER_CREATE, societyId)
        return view(voucherService.recordContra(societyId, request.toCommand(), access.userId, today()))
    }

    @PostMapping("/api/societies/{societyId}/finance/journals")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.FINANCE_JOURNAL_CREATE)
    fun journal(@PathVariable societyId: Long, @Valid @RequestBody request: JournalRequest): VoucherResponse {
        val access = guard.require(Permissions.FINANCE_JOURNAL_CREATE, societyId)
        return view(voucherService.recordJournal(societyId, request.toCommand(), access.userId, today()))
    }

    // ---- Waiting vouchers: the maker edits or withdraws; someone else approves or rejects ----

    @PutMapping("/api/finance/payments/{id}")
    @RequiresPermission(Permissions.FINANCE_VOUCHER_CREATE)
    fun updatePayment(@PathVariable id: Long, @Valid @RequestBody request: PaymentRequest): VoucherResponse {
        val access = guard.require(Permissions.FINANCE_VOUCHER_CREATE, societyOf(id))
        voucherService.updatePayment(id, request.toCommand(), access.userId, today())
        return view(id)
    }

    @PutMapping("/api/finance/journals/{id}")
    @RequiresPermission(Permissions.FINANCE_JOURNAL_CREATE)
    fun updateJournal(@PathVariable id: Long, @Valid @RequestBody request: JournalRequest): VoucherResponse {
        val access = guard.require(Permissions.FINANCE_JOURNAL_CREATE, societyOf(id))
        voucherService.updateJournal(id, request.toCommand(), access.userId, today())
        return view(id)
    }

    @PostMapping("/api/finance/vouchers/{id}/withdraw")
    @RequiresPermission(Permissions.FINANCE_VOUCHER_CREATE)
    fun withdraw(@PathVariable id: Long, @Valid @RequestBody(required = false) request: ReasonRequest?): VoucherResponse {
        val access = guard.require(Permissions.FINANCE_VOUCHER_CREATE, societyOf(id))
        voucherService.withdraw(id, access.userId, request?.reason)
        return view(id)
    }

    @PostMapping("/api/finance/vouchers/{id}/approve")
    @RequiresPermission(Permissions.FINANCE_VOUCHER_APPROVE)
    fun approve(@PathVariable id: Long): VoucherResponse {
        val access = guard.require(Permissions.FINANCE_VOUCHER_APPROVE, societyOf(id))
        voucherService.approve(id, access.userId, today())
        return view(id)
    }

    @PostMapping("/api/finance/vouchers/{id}/reject")
    @RequiresPermission(Permissions.FINANCE_VOUCHER_APPROVE)
    fun reject(@PathVariable id: Long, @Valid @RequestBody request: ReasonRequest): VoucherResponse {
        val access = guard.require(Permissions.FINANCE_VOUCHER_APPROVE, societyOf(id))
        voucherService.reject(id, access.userId, request.reason ?: "")
        return view(id)
    }

    // Returns the voided voucher; it names its reversal.
    @PostMapping("/api/finance/vouchers/{id}/void")
    @RequiresPermission(Permissions.FINANCE_VOUCHER_UPDATE)
    fun void(@PathVariable id: Long, @Valid @RequestBody request: ReasonRequest): VoucherResponse {
        val access = guard.require(Permissions.FINANCE_VOUCHER_UPDATE, societyOf(id))
        voucherService.void(id, access.userId, request.reason ?: "", today())
        return view(id)
    }

    // ---- Read ----

    @GetMapping("/api/finance/vouchers/{id}")
    @RequiresPermission(Permissions.FINANCE_VOUCHER_VIEW)
    fun get(@PathVariable id: Long): VoucherResponse {
        guard.require(Permissions.FINANCE_VOUCHER_VIEW, societyOf(id))
        return view(id)
    }

    // Newest first. All filters optional; `q` searches number, payee, narration and reference;
    // status=PENDING_APPROVAL is the approvals queue.
    @GetMapping("/api/societies/{societyId}/finance/vouchers")
    @RequiresPermission(Permissions.FINANCE_VOUCHER_VIEW)
    fun search(
        @PathVariable societyId: Long,
        @RequestParam(required = false) type: List<VoucherType>?,
        @RequestParam(required = false) status: List<VoucherStatus>?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) accountId: Long?,
        @RequestParam(required = false) flatId: Long?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): List<VoucherResponse> {
        guard.require(Permissions.FINANCE_VOUCHER_VIEW, societyId)
        val vouchers = voucherService.search(
            VoucherSearch(
                societyId = societyId,
                types = type?.takeIf { it.isNotEmpty() },
                statuses = status?.takeIf { it.isNotEmpty() },
                from = from,
                to = to,
                accountId = accountId,
                flatId = flatId,
                text = q,
                page = page.coerceAtLeast(0),
                size = size.coerceIn(1, 200),
            )
        )
        return views(societyId, vouchers)
    }

    // Every voucher in the books over the period, oldest first (voided ones and their reversals too).
    @GetMapping("/api/societies/{societyId}/finance/day-book")
    @RequiresPermission(Permissions.FINANCE_VOUCHER_VIEW)
    fun dayBook(
        @PathVariable societyId: Long,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "200") size: Int,
    ): List<VoucherResponse> {
        guard.require(Permissions.FINANCE_VOUCHER_VIEW, societyId)
        val period = ledgerService.period(societyId, from, to, today())
        val vouchers = voucherService.search(
            VoucherSearch(
                societyId = societyId,
                statuses = VoucherStatus.IN_BOOKS,
                from = period.from,
                to = period.to,
                oldestFirst = true,
                page = page.coerceAtLeast(0),
                size = size.coerceIn(1, 500),
            )
        )
        return views(societyId, vouchers)
    }

    // Opening, received, paid and closing for each cash and bank ledger. The cash book and a bank book are
    // the ledger of that account (GET /api/finance/accounts/{id}/ledger).
    @GetMapping("/api/societies/{societyId}/finance/cash-bank")
    @RequiresPermission(Permissions.FINANCE_VOUCHER_VIEW)
    fun cashAndBank(
        @PathVariable societyId: Long,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): CashAndBankResponse {
        guard.require(Permissions.FINANCE_VOUCHER_VIEW, societyId)
        val period = ledgerService.period(societyId, from, to, today())
        return CashAndBankResponse.from(period.from, period.to, ledgerService.cashAndBank(societyId, period))
    }

    // ---- Documents ----

    @PostMapping("/api/finance/vouchers/{id}/documents", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.FINANCE_VOUCHER_CREATE)
    fun addDocument(
        @PathVariable id: Long,
        @RequestPart("file") file: MultipartFile,
        @RequestParam(defaultValue = "OTHER") documentType: DocumentType,
    ): VoucherResponse {
        val access = guard.require(Permissions.FINANCE_VOUCHER_CREATE, societyOf(id))
        documentService.add(id, file.bytes, file.originalFilename, documentType, access.userId)
        return view(id)
    }

    @GetMapping("/api/finance/documents/{id}")
    @RequiresPermission(Permissions.FINANCE_VOUCHER_VIEW)
    fun document(@PathVariable id: Long): ResponseEntity<ByteArray> {
        val document = documentService.get(id)
        guard.require(Permissions.FINANCE_VOUCHER_VIEW, documentService.societyOf(document))
        val file = documentService.load(document)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.contentType))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline().filename(document.filename ?: "document-$id", Charsets.UTF_8).build().toString(),
            )
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(file.data)
    }

    @DeleteMapping("/api/finance/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.FINANCE_VOUCHER_CREATE)
    fun removeDocument(@PathVariable id: Long) {
        val document = documentService.get(id)
        val access = guard.require(Permissions.FINANCE_VOUCHER_CREATE, documentService.societyOf(document))
        documentService.remove(id, access.userId)
    }

    // ---- Books: lock date and default accounts ----

    @PutMapping("/api/societies/{societyId}/finance/lock")
    @RequiresPermission(Permissions.FINANCE_ACCOUNT_UPDATE)
    fun lock(@PathVariable societyId: Long, @Valid @RequestBody request: LockRequest): BooksResponse {
        val access = guard.require(Permissions.FINANCE_ACCOUNT_UPDATE, societyId)
        booksService.setLock(societyId, request.lockedUntil, request.reason, access.userId, today())
        return BooksResponse.from(booksService.state(societyId))
    }

    @PutMapping("/api/societies/{societyId}/finance/defaults")
    @RequiresPermission(Permissions.FINANCE_ACCOUNT_UPDATE)
    fun defaults(@PathVariable societyId: Long, @RequestBody request: BookDefaultsRequest): BooksResponse {
        val access = guard.require(Permissions.FINANCE_ACCOUNT_UPDATE, societyId)
        booksService.setDefaults(societyId, request.toDefaults(), access.userId)
        return BooksResponse.from(booksService.state(societyId))
    }

    private fun societyOf(voucherId: Long): Long = voucherService.get(voucherId).societyId

    private fun view(id: Long): VoucherResponse {
        val voucher = voucherService.get(id)
        return VoucherResponse.from(voucher, contextService.contextFor(voucher.societyId, listOf(voucher)))
    }

    private fun views(societyId: Long, vouchers: List<Voucher>): List<VoucherResponse> {
        val context = contextService.contextFor(societyId, vouchers)
        return vouchers.map { VoucherResponse.from(it, context) }
    }
}
