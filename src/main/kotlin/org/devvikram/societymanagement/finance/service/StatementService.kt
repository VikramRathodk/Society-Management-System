package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.finance.model.AccountType
import org.devvikram.societymanagement.finance.model.FinanceAccount
import org.devvikram.societymanagement.finance.model.VoucherType
import org.devvikram.societymanagement.finance.repository.AccountTotals
import org.devvikram.societymanagement.finance.repository.VoucherRepository
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

// An amount against one ledger (natural sign for its type unless the statement says otherwise).
data class StatementLine(val account: FinanceAccount, val amount: BigDecimal)

// Ledgers under one top-level group of a type (e.g. "Utilities" under Expenses). A ledger placed directly
// under the root is its own group.
data class StatementGroup(val group: FinanceAccount, val lines: List<StatementLine>) {
    val total: BigDecimal get() = lines.fold(ZERO) { sum, it -> sum + it.amount }
}

data class TrialBalanceRow(val account: FinanceAccount, val debit: BigDecimal, val credit: BigDecimal)

data class TrialBalance(val asOf: LocalDate, val rows: List<TrialBalanceRow>) {
    val totalDebit: BigDecimal get() = rows.fold(ZERO) { s, r -> s + r.debit }
    val totalCredit: BigDecimal get() = rows.fold(ZERO) { s, r -> s + r.credit }
    val balanced: Boolean get() = totalDebit.compareTo(totalCredit) == 0
}

data class IncomeExpenditure(val from: LocalDate, val to: LocalDate, val income: List<StatementGroup>, val expenditure: List<StatementGroup>) {
    val totalIncome: BigDecimal get() = income.fold(ZERO) { s, g -> s + g.total }
    val totalExpenditure: BigDecimal get() = expenditure.fold(ZERO) { s, g -> s + g.total }
    // Negative is a deficit.
    val surplus: BigDecimal get() = totalIncome - totalExpenditure
}

data class ReceiptsPayments(
    val from: LocalDate,
    val to: LocalDate,
    val opening: List<StatementLine>,
    val receipts: List<StatementGroup>,
    val payments: List<StatementGroup>,
    val closing: List<StatementLine>,
) {
    val openingTotal: BigDecimal get() = opening.fold(ZERO) { s, l -> s + l.amount }
    val receiptsTotal: BigDecimal get() = receipts.fold(ZERO) { s, g -> s + g.total }
    val paymentsTotal: BigDecimal get() = payments.fold(ZERO) { s, g -> s + g.total }
    val closingTotal: BigDecimal get() = closing.fold(ZERO) { s, l -> s + l.amount }
}

data class BalanceSheet(
    val asOf: LocalDate,
    val assets: List<StatementGroup>,
    val liabilities: List<StatementGroup>,
    val funds: List<StatementGroup>,
    // Income minus expenditure of everything up to asOf, shown with the funds until year closing books it.
    val surplus: BigDecimal,
) {
    val totalAssets: BigDecimal get() = assets.fold(ZERO) { s, g -> s + g.total }
    val totalLiabilities: BigDecimal get() = liabilities.fold(ZERO) { s, g -> s + g.total }
    val totalFunds: BigDecimal get() = funds.fold(ZERO) { s, g -> s + g.total } + surplus
    val balanced: Boolean get() = totalAssets.compareTo(totalLiabilities + totalFunds) == 0
}

// One month of the resident summary: totals by top-level group only, never single entries.
data class MonthSummary(
    val month: YearMonth,
    val income: List<GroupTotal>,
    val expenditure: List<GroupTotal>,
    val closingCashAndBank: BigDecimal,
) {
    val totalIncome: BigDecimal get() = income.fold(ZERO) { s, g -> s + g.amount }
    val totalExpenditure: BigDecimal get() = expenditure.fold(ZERO) { s, g -> s + g.amount }
}

data class GroupTotal(val name: String, val amount: BigDecimal)

private val ZERO: BigDecimal = BigDecimal.ZERO.setScale(2)

// The financial statements, all computed from the voucher lines in the books (POSTED and VOID with their
// reversals), so every total reconciles to the ledgers. Zero rows are left out.
@Service
class StatementService(
    private val voucherRepository: VoucherRepository,
    private val chartService: ChartService,
    private val booksService: BooksService,
    private val settings: SocietySettingsService,
) {
    // Each ledger's balance on its own side: debit for assets and expenses (or the other way round when it
    // went negative), so the two columns always add up to the same total.
    @Transactional(readOnly = true)
    fun trialBalance(societyId: Long, asOf: LocalDate): TrialBalance {
        booksService.requireBooks(societyId)
        val totals = voucherRepository.totalsByAccount(societyId, asOf).associateBy { it.accountId }
        val rows = chartService.accounts(societyId).filterNot { it.isGroup }.mapNotNull { account ->
            val t = totals[account.id] ?: return@mapNotNull null
            val net = (t.debit - t.credit).setScale(2)
            when (net.signum()) {
                1 -> TrialBalanceRow(account, net, ZERO)
                -1 -> TrialBalanceRow(account, ZERO, net.negate())
                else -> null
            }
        }
        return TrialBalance(asOf, rows)
    }

    @Transactional(readOnly = true)
    fun incomeExpenditure(societyId: Long, from: LocalDate, to: LocalDate): IncomeExpenditure {
        booksService.requireBooks(societyId)
        val chart = Chart(chartService.accounts(societyId))
        val totals = voucherRepository.totalsByAccountBetween(societyId, from, to)
        return IncomeExpenditure(from, to, chart.grouped(AccountType.INCOME, totals), chart.grouped(AccountType.EXPENSE, totals))
    }

    // Money in and out of the cash and bank ledgers over the period, by the account on the other side.
    // Transfers between cash and bank cancel out; each account shows on the side of its net movement.
    @Transactional(readOnly = true)
    fun receiptsPayments(societyId: Long, from: LocalDate, to: LocalDate): ReceiptsPayments {
        booksService.requireBooks(societyId)
        val accounts = chartService.accounts(societyId)
        val chart = Chart(accounts)
        val money = chartService.moneyAccountIds(societyId)
        val moneyAccounts = accounts.filter { it.id in money }

        // The opening voucher is the opening balance, not money received, even when the period starts on the
        // day the books do.
        val periodLines = voucherRepository.linesBetween(societyId, from, to)
        val openingInPeriod = periodLines.filter { it.type == VoucherType.OPENING && it.accountId in money }
            .groupBy { it.accountId }.mapValues { (_, lines) -> lines.fold(BigDecimal.ZERO) { s, l -> s + l.debit - l.credit } }

        val net = mutableMapOf<Long, BigDecimal>() // credit - debit of the other side: money coming in
        periodLines.filter { it.type != VoucherType.OPENING }.groupBy { it.voucherId }.values
            .filter { lines -> lines.any { it.accountId in money } }
            .flatten()
            .filter { it.accountId !in money }
            .forEach { net.merge(it.accountId, it.credit - it.debit, BigDecimal::add) }
        val byId = accounts.associateBy { it.id!! }
        val receipts = net.filterValues { it.signum() > 0 }.map { (id, amount) -> StatementLine(byId.getValue(id), amount.setScale(2)) }
        val payments = net.filterValues { it.signum() < 0 }.map { (id, amount) -> StatementLine(byId.getValue(id), amount.negate().setScale(2)) }

        fun moneyAt(totals: Map<Long, AccountTotals>, extra: Map<Long, BigDecimal> = emptyMap()) = moneyAccounts.mapNotNull { account ->
            val t = totals[account.id]
            val amount = (t?.let { account.type.natural(it.debit, it.credit) } ?: BigDecimal.ZERO) + (extra[account.id] ?: BigDecimal.ZERO)
            StatementLine(account, amount.setScale(2)).takeIf { it.amount.signum() != 0 }
        }
        val opening = moneyAt(voucherRepository.totalsByAccount(societyId, from.minusDays(1)).associateBy { it.accountId }, openingInPeriod)
        val closing = moneyAt(voucherRepository.totalsByAccount(societyId, to).associateBy { it.accountId })
        return ReceiptsPayments(from, to, opening, chart.group(receipts), chart.group(payments), closing)
    }

    @Transactional(readOnly = true)
    fun balanceSheet(societyId: Long, asOf: LocalDate): BalanceSheet {
        booksService.requireBooks(societyId)
        val chart = Chart(chartService.accounts(societyId))
        val totals = voucherRepository.totalsByAccount(societyId, asOf)
        val income = chart.grouped(AccountType.INCOME, totals).fold(ZERO) { s, g -> s + g.total }
        val expenditure = chart.grouped(AccountType.EXPENSE, totals).fold(ZERO) { s, g -> s + g.total }
        return BalanceSheet(
            asOf,
            chart.grouped(AccountType.ASSET, totals),
            chart.grouped(AccountType.LIABILITY, totals),
            chart.grouped(AccountType.FUND, totals),
            income - expenditure,
        )
    }

    // Month by month over [from, to]: income and expenditure by top-level group, and cash + bank at month end.
    @Transactional(readOnly = true)
    fun monthlySummary(societyId: Long, from: LocalDate, to: LocalDate): List<MonthSummary> {
        booksService.requireBooks(societyId)
        val chart = Chart(chartService.accounts(societyId))
        val money = chartService.moneyAccountIds(societyId)
        val months = generateSequence(YearMonth.from(from)) { it.plusMonths(1) }.takeWhile { !it.isAfter(YearMonth.from(to)) }.toList()
        require(months.size <= 36) { "Ask for at most 36 months" }
        return months.map { month ->
            val start = maxOf(month.atDay(1), from)
            val end = minOf(month.atEndOfMonth(), to)
            val totals = voucherRepository.totalsByAccountBetween(societyId, start, end)
            val cash = voucherRepository.totalsByAccount(societyId, end).filter { it.accountId in money }
                .fold(ZERO) { s, t -> s + t.debit - t.credit }.setScale(2)
            MonthSummary(
                month,
                chart.grouped(AccountType.INCOME, totals).map { GroupTotal(it.group.name, it.total) },
                chart.grouped(AccountType.EXPENSE, totals).map { GroupTotal(it.group.name, it.total) },
                cash,
            )
        }
    }

    // May this user see the resident summary? Society-wide report readers always; others (residents) only
    // when the society shares it.
    fun summaryShared(societyId: Long): Boolean = settings.boolean(societyId, SocietySettingKeys.RESIDENTS_SEE_FINANCE_SUMMARY)

    // Groups ledgers under the child of their type's root that holds them.
    private class Chart(accounts: List<FinanceAccount>) {
        private val byId = accounts.associateBy { it.id!! }
        private val ledgers = accounts.filterNot { it.isGroup }

        fun headOf(account: FinanceAccount): FinanceAccount {
            var current = account
            while (true) {
                val parent = current.parentId?.let(byId::get) ?: return current
                if (parent.parentId == null) return current
                current = parent
            }
        }

        fun grouped(type: AccountType, totals: List<AccountTotals>): List<StatementGroup> {
            val byAccount = totals.associateBy { it.accountId }
            return group(ledgers.filter { it.type == type }.mapNotNull { account ->
                val t = byAccount[account.id] ?: return@mapNotNull null
                StatementLine(account, account.type.natural(t.debit, t.credit).setScale(2)).takeIf { it.amount.signum() != 0 }
            })
        }

        fun group(lines: List<StatementLine>): List<StatementGroup> =
            lines.groupBy { headOf(it.account) }
                .map { (head, group) -> StatementGroup(head, group.sortedBy { it.account.code }) }
                .sortedBy { it.group.code }
    }
}
