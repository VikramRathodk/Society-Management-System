package org.devvikram.societymanagement.finance.controller

import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.common.export.Csv
import org.devvikram.societymanagement.finance.dto.BalanceSheetResponse
import org.devvikram.societymanagement.finance.dto.FinanceSummaryResponse
import org.devvikram.societymanagement.finance.dto.IncomeExpenditureResponse
import org.devvikram.societymanagement.finance.dto.MonthSummaryResponse
import org.devvikram.societymanagement.finance.dto.ReceiptsPaymentsResponse
import org.devvikram.societymanagement.finance.dto.TrialBalanceResponse
import org.devvikram.societymanagement.finance.model.VoucherStatus
import org.devvikram.societymanagement.finance.repository.VoucherSearch
import org.devvikram.societymanagement.finance.service.ChartService
import org.devvikram.societymanagement.finance.service.LedgerService
import org.devvikram.societymanagement.finance.service.StatementGroup
import org.devvikram.societymanagement.finance.service.StatementService
import org.devvikram.societymanagement.finance.service.VoucherService
import org.springframework.beans.factory.annotation.Value
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneId

// Financial statements (COMMITTEE and above read them, and export CSV), plus the monthly summary that
// residents see when the society shares it. Periods default like the ledger: the current financial year
// (not before the books start) to today; "as of" defaults to today.
@RestController
class FinanceReportController(
    private val statementService: StatementService,
    private val ledgerService: LedgerService,
    private val voucherService: VoucherService,
    private val chartService: ChartService,
    private val accessService: AccessService,
    private val guard: FinanceGuard,
    @Value("\${app.report.zone}") private val reportZone: ZoneId,
) {
    private fun today() = LocalDate.now(reportZone)

    @GetMapping("/api/societies/{societyId}/finance/reports/trial-balance")
    @RequiresPermission(Permissions.FINANCE_REPORT_VIEW)
    fun trialBalance(@PathVariable societyId: Long, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate?): TrialBalanceResponse {
        guard.require(Permissions.FINANCE_REPORT_VIEW, societyId)
        return TrialBalanceResponse.from(statementService.trialBalance(societyId, asOf ?: today()))
    }

    @GetMapping("/api/societies/{societyId}/finance/reports/income-expenditure")
    @RequiresPermission(Permissions.FINANCE_REPORT_VIEW)
    fun incomeExpenditure(
        @PathVariable societyId: Long,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): IncomeExpenditureResponse {
        guard.require(Permissions.FINANCE_REPORT_VIEW, societyId)
        val period = ledgerService.period(societyId, from, to, today())
        return IncomeExpenditureResponse.from(statementService.incomeExpenditure(societyId, period.from, period.to))
    }

    @GetMapping("/api/societies/{societyId}/finance/reports/receipts-payments")
    @RequiresPermission(Permissions.FINANCE_REPORT_VIEW)
    fun receiptsPayments(
        @PathVariable societyId: Long,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ReceiptsPaymentsResponse {
        guard.require(Permissions.FINANCE_REPORT_VIEW, societyId)
        val period = ledgerService.period(societyId, from, to, today())
        return ReceiptsPaymentsResponse.from(statementService.receiptsPayments(societyId, period.from, period.to))
    }

    @GetMapping("/api/societies/{societyId}/finance/reports/balance-sheet")
    @RequiresPermission(Permissions.FINANCE_REPORT_VIEW)
    fun balanceSheet(@PathVariable societyId: Long, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate?): BalanceSheetResponse {
        guard.require(Permissions.FINANCE_REPORT_VIEW, societyId)
        return BalanceSheetResponse.from(statementService.balanceSheet(societyId, asOf ?: today()))
    }

    // Residents see this only when the society shares it (residentsSeeFinanceSummary); report readers always.
    // Totals by group per month and the cash + bank balance: never single entries or payees.
    @GetMapping("/api/societies/{societyId}/finance/summary")
    @RequiresPermission(Permissions.FINANCE_SUMMARY_VIEW)
    fun summary(
        @PathVariable societyId: Long,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): FinanceSummaryResponse {
        val access = accessService.current()
        access.requireIn(Permissions.FINANCE_SUMMARY_VIEW, societyId)
        if (!access.canSocietyWide(Permissions.FINANCE_REPORT_VIEW, societyId) && !statementService.summaryShared(societyId)) {
            throw AccessDeniedException("This society doesn't share its finance summary")
        }
        val period = ledgerService.period(societyId, from, to, today())
        return FinanceSummaryResponse(period.from, period.to,
            statementService.monthlySummary(societyId, period.from, period.to).map(MonthSummaryResponse::from))
    }

    // ---- CSV ----

    @GetMapping("/api/societies/{societyId}/finance/reports/trial-balance/export")
    @RequiresPermission(Permissions.FINANCE_REPORT_EXPORT)
    fun exportTrialBalance(@PathVariable societyId: Long, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate?): ResponseEntity<ByteArray> {
        guard.require(Permissions.FINANCE_REPORT_EXPORT, societyId)
        val tb = statementService.trialBalance(societyId, asOf ?: today())
        val rows = tb.rows.map { listOf(it.account.code, it.account.name, it.account.type, it.debit, it.credit) } +
            listOf(listOf(null, "Total", null, tb.totalDebit, tb.totalCredit))
        return Csv.download("trial-balance-$societyId-${tb.asOf}.csv", Csv.write(listOf("code", "account", "type", "debit", "credit"), rows))
    }

    @GetMapping("/api/societies/{societyId}/finance/reports/income-expenditure/export")
    @RequiresPermission(Permissions.FINANCE_REPORT_EXPORT)
    fun exportIncomeExpenditure(
        @PathVariable societyId: Long,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<ByteArray> {
        guard.require(Permissions.FINANCE_REPORT_EXPORT, societyId)
        val period = ledgerService.period(societyId, from, to, today())
        val ie = statementService.incomeExpenditure(societyId, period.from, period.to)
        val rows = groupRows("Income", ie.income) + groupRows("Expenditure", ie.expenditure) +
            listOf(listOf("Total", null, null, "Income", ie.totalIncome), listOf("Total", null, null, "Expenditure", ie.totalExpenditure),
                listOf("Total", null, null, if (ie.surplus.signum() >= 0) "Surplus" else "Deficit", ie.surplus))
        return Csv.download("income-expenditure-$societyId-${period.from}-to-${period.to}.csv", Csv.write(STATEMENT_HEADER, rows))
    }

    @GetMapping("/api/societies/{societyId}/finance/reports/receipts-payments/export")
    @RequiresPermission(Permissions.FINANCE_REPORT_EXPORT)
    fun exportReceiptsPayments(
        @PathVariable societyId: Long,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<ByteArray> {
        guard.require(Permissions.FINANCE_REPORT_EXPORT, societyId)
        val period = ledgerService.period(societyId, from, to, today())
        val rp = statementService.receiptsPayments(societyId, period.from, period.to)
        val rows = rp.opening.map { listOf("Opening balance", null, it.account.code, it.account.name, it.amount) } +
            groupRows("Receipts", rp.receipts) + groupRows("Payments", rp.payments) +
            rp.closing.map { listOf("Closing balance", null, it.account.code, it.account.name, it.amount) }
        return Csv.download("receipts-payments-$societyId-${period.from}-to-${period.to}.csv", Csv.write(STATEMENT_HEADER, rows))
    }

    @GetMapping("/api/societies/{societyId}/finance/reports/balance-sheet/export")
    @RequiresPermission(Permissions.FINANCE_REPORT_EXPORT)
    fun exportBalanceSheet(@PathVariable societyId: Long, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate?): ResponseEntity<ByteArray> {
        guard.require(Permissions.FINANCE_REPORT_EXPORT, societyId)
        val bs = statementService.balanceSheet(societyId, asOf ?: today())
        val rows = groupRows("Assets", bs.assets) + groupRows("Liabilities", bs.liabilities) + groupRows("Funds", bs.funds) +
            listOf(listOf("Funds", null, null, "Surplus / (deficit) to date", bs.surplus),
                listOf("Total", null, null, "Assets", bs.totalAssets),
                listOf("Total", null, null, "Liabilities and funds", bs.totalLiabilities + bs.totalFunds))
        return Csv.download("balance-sheet-$societyId-${bs.asOf}.csv", Csv.write(STATEMENT_HEADER, rows))
    }

    @GetMapping("/api/finance/accounts/{id}/ledger/export")
    @RequiresPermission(Permissions.FINANCE_REPORT_EXPORT)
    fun exportLedger(
        @PathVariable id: Long,
        @RequestParam(required = false) flatId: Long?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<ByteArray> {
        guard.require(Permissions.FINANCE_REPORT_EXPORT, chartService.get(id).societyId)
        val ledger = ledgerService.ledger(id, flatId, from, to, today())
        val rows = listOf(listOf(ledger.from, null, null, "Opening balance", null, null, null, ledger.opening)) +
            ledger.lines.map { listOf(it.row.date, it.row.number, it.row.type, it.row.narration, it.row.payee ?: it.flatLabel, it.row.debit, it.row.credit, it.balance) } +
            listOf(listOf(ledger.to, null, null, "Closing balance", null, ledger.totalDebit, ledger.totalCredit, ledger.closing))
        return Csv.download("ledger-${ledger.account.code}-${ledger.from}-to-${ledger.to}.csv",
            Csv.write(listOf("date", "voucher", "type", "narration", "party", "debit", "credit", "balance"), rows))
    }

    @GetMapping("/api/societies/{societyId}/finance/day-book/export")
    @RequiresPermission(Permissions.FINANCE_REPORT_EXPORT)
    fun exportDayBook(
        @PathVariable societyId: Long,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<ByteArray> {
        guard.require(Permissions.FINANCE_REPORT_EXPORT, societyId)
        val period = ledgerService.period(societyId, from, to, today())
        val vouchers = voucherService.search(
            VoucherSearch(societyId, statuses = VoucherStatus.IN_BOOKS, from = period.from, to = period.to, oldestFirst = true, size = MAX_EXPORT + 1)
        )
        require(vouchers.size <= MAX_EXPORT) { "More than $MAX_EXPORT entries; export a shorter period" }
        val accounts = chartService.accounts(societyId).associateBy { it.id!! }
        val rows = vouchers.flatMap { v ->
            v.lines.map { l ->
                val account = accounts.getValue(l.accountId)
                listOf(v.voucherDate, v.number, v.type, v.status, v.narration, v.payee, account.code, account.name, l.debit, l.credit)
            }
        }
        return Csv.download("day-book-$societyId-${period.from}-to-${period.to}.csv",
            Csv.write(listOf("date", "voucher", "type", "status", "narration", "payee", "code", "account", "debit", "credit"), rows))
    }

    private fun groupRows(section: String, groups: List<StatementGroup>): List<List<Any?>> =
        groups.flatMap { group -> group.lines.map { listOf(section, group.group.name, it.account.code, it.account.name, it.amount) } }

    private companion object {
        val STATEMENT_HEADER = listOf("section", "group", "code", "account", "amount")
        const val MAX_EXPORT = 5000
    }
}
