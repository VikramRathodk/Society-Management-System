package org.devvikram.societymanagement.finance.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.finance.dto.AccountResponse
import org.devvikram.societymanagement.finance.dto.AddBankAccountRequest
import org.devvikram.societymanagement.finance.dto.CreateAccountRequest
import org.devvikram.societymanagement.finance.dto.LedgerResponse
import org.devvikram.societymanagement.finance.dto.UpdateAccountRequest
import org.devvikram.societymanagement.finance.dto.UpdateBankAccountRequest
import org.devvikram.societymanagement.finance.service.ChartService
import org.devvikram.societymanagement.finance.service.LedgerService
import org.springframework.beans.factory.annotation.Value
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneId

// The chart of accounts, bank accounts and account ledgers.
@RestController
class FinanceAccountController(
    private val chartService: ChartService,
    private val ledgerService: LedgerService,
    private val guard: FinanceGuard,
    @Value("\${app.report.zone}") private val reportZone: ZoneId,
) {
    // The whole chart by code, with balances as of `asOf` (default today).
    @GetMapping("/api/societies/{societyId}/finance/accounts")
    @RequiresPermission(Permissions.FINANCE_ACCOUNT_VIEW)
    fun accounts(
        @PathVariable societyId: Long,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate?,
    ): List<AccountResponse> {
        guard.require(Permissions.FINANCE_ACCOUNT_VIEW, societyId)
        return chartService.chart(societyId, asOf ?: LocalDate.now(reportZone)).map(AccountResponse::from)
    }

    @PostMapping("/api/societies/{societyId}/finance/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.FINANCE_ACCOUNT_UPDATE)
    fun create(@PathVariable societyId: Long, @Valid @RequestBody request: CreateAccountRequest): AccountResponse {
        val access = guard.require(Permissions.FINANCE_ACCOUNT_UPDATE, societyId)
        return viewOf(chartService.create(societyId, request.toCommand(), access.userId))
    }

    @PatchMapping("/api/finance/accounts/{id}")
    @RequiresPermission(Permissions.FINANCE_ACCOUNT_UPDATE)
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateAccountRequest): AccountResponse {
        val access = guard.require(Permissions.FINANCE_ACCOUNT_UPDATE, chartService.get(id).societyId)
        chartService.update(id, request.toCommand(), access.userId)
        return viewOf(id)
    }

    @DeleteMapping("/api/finance/accounts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.FINANCE_ACCOUNT_UPDATE)
    fun delete(@PathVariable id: Long) {
        val access = guard.require(Permissions.FINANCE_ACCOUNT_UPDATE, chartService.get(id).societyId)
        chartService.delete(id, access.userId)
    }

    @PostMapping("/api/societies/{societyId}/finance/bank-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.FINANCE_ACCOUNT_UPDATE)
    fun addBank(@PathVariable societyId: Long, @Valid @RequestBody request: AddBankAccountRequest): AccountResponse {
        val access = guard.require(Permissions.FINANCE_ACCOUNT_UPDATE, societyId)
        return viewOf(chartService.addBank(societyId, request.toCommand(), access.userId))
    }

    @PatchMapping("/api/finance/bank-accounts/{id}")
    @RequiresPermission(Permissions.FINANCE_ACCOUNT_UPDATE)
    fun updateBank(@PathVariable id: Long, @Valid @RequestBody request: UpdateBankAccountRequest): AccountResponse {
        val access = guard.require(Permissions.FINANCE_ACCOUNT_UPDATE, chartService.get(id).societyId)
        chartService.updateBank(id, request.toCommand(), access.userId)
        return viewOf(id)
    }

    // Opening balance, every entry with its running balance, closing balance. Defaults: from the start of
    // the current financial year (not before the books start) to today. flatId narrows a member account.
    @GetMapping("/api/finance/accounts/{id}/ledger")
    @RequiresPermission(Permissions.FINANCE_VOUCHER_VIEW)
    fun ledger(
        @PathVariable id: Long,
        @RequestParam(required = false) flatId: Long?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): LedgerResponse {
        guard.require(Permissions.FINANCE_VOUCHER_VIEW, chartService.get(id).societyId)
        return LedgerResponse.from(ledgerService.ledger(id, flatId, from, to, LocalDate.now(reportZone)))
    }

    private fun viewOf(id: Long): AccountResponse {
        val account = chartService.get(id)
        return chartService.chart(account.societyId, LocalDate.now(reportZone)).first { it.account.id == id }
            .let(AccountResponse::from)
    }
}
