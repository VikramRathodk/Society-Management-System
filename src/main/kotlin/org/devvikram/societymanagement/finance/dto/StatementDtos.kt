package org.devvikram.societymanagement.finance.dto

import org.devvikram.societymanagement.finance.model.AccountType
import org.devvikram.societymanagement.finance.service.BalanceSheet
import org.devvikram.societymanagement.finance.service.IncomeExpenditure
import org.devvikram.societymanagement.finance.service.MonthSummary
import org.devvikram.societymanagement.finance.service.ReceiptsPayments
import org.devvikram.societymanagement.finance.service.StatementGroup
import org.devvikram.societymanagement.finance.service.StatementLine
import org.devvikram.societymanagement.finance.service.TrialBalance
import java.math.BigDecimal
import java.time.LocalDate

data class StatementLineResponse(val accountId: Long, val code: String, val name: String, val amount: BigDecimal) {
    companion object {
        fun from(line: StatementLine) = StatementLineResponse(line.account.id!!, line.account.code, line.account.name, line.amount)
    }
}

data class StatementGroupResponse(
    val groupId: Long,
    val code: String,
    val name: String,
    val total: BigDecimal,
    val lines: List<StatementLineResponse>,
) {
    companion object {
        fun from(group: StatementGroup) = StatementGroupResponse(
            group.group.id!!, group.group.code, group.group.name, group.total, group.lines.map(StatementLineResponse::from),
        )
    }
}

data class TrialBalanceRowResponse(
    val accountId: Long,
    val code: String,
    val name: String,
    val type: AccountType,
    val debit: BigDecimal,
    val credit: BigDecimal,
)

data class TrialBalanceResponse(
    val asOf: LocalDate,
    val rows: List<TrialBalanceRowResponse>,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
    val balanced: Boolean,
) {
    companion object {
        fun from(tb: TrialBalance) = TrialBalanceResponse(
            tb.asOf,
            tb.rows.map { TrialBalanceRowResponse(it.account.id!!, it.account.code, it.account.name, it.account.type, it.debit, it.credit) },
            tb.totalDebit, tb.totalCredit, tb.balanced,
        )
    }
}

data class IncomeExpenditureResponse(
    val from: LocalDate,
    val to: LocalDate,
    val income: List<StatementGroupResponse>,
    val expenditure: List<StatementGroupResponse>,
    val totalIncome: BigDecimal,
    val totalExpenditure: BigDecimal,
    // Negative is a deficit.
    val surplus: BigDecimal,
) {
    companion object {
        fun from(ie: IncomeExpenditure) = IncomeExpenditureResponse(
            ie.from, ie.to, ie.income.map(StatementGroupResponse::from), ie.expenditure.map(StatementGroupResponse::from),
            ie.totalIncome, ie.totalExpenditure, ie.surplus,
        )
    }
}

data class ReceiptsPaymentsResponse(
    val from: LocalDate,
    val to: LocalDate,
    val opening: List<StatementLineResponse>,
    val openingTotal: BigDecimal,
    val receipts: List<StatementGroupResponse>,
    val receiptsTotal: BigDecimal,
    val payments: List<StatementGroupResponse>,
    val paymentsTotal: BigDecimal,
    val closing: List<StatementLineResponse>,
    val closingTotal: BigDecimal,
) {
    companion object {
        fun from(rp: ReceiptsPayments) = ReceiptsPaymentsResponse(
            rp.from, rp.to,
            rp.opening.map(StatementLineResponse::from), rp.openingTotal,
            rp.receipts.map(StatementGroupResponse::from), rp.receiptsTotal,
            rp.payments.map(StatementGroupResponse::from), rp.paymentsTotal,
            rp.closing.map(StatementLineResponse::from), rp.closingTotal,
        )
    }
}

data class BalanceSheetResponse(
    val asOf: LocalDate,
    val assets: List<StatementGroupResponse>,
    val liabilities: List<StatementGroupResponse>,
    val funds: List<StatementGroupResponse>,
    // Accumulated income minus expenditure, shown with the funds.
    val surplus: BigDecimal,
    val totalAssets: BigDecimal,
    val totalLiabilities: BigDecimal,
    // Funds including the surplus.
    val totalFunds: BigDecimal,
    val balanced: Boolean,
) {
    companion object {
        fun from(bs: BalanceSheet) = BalanceSheetResponse(
            bs.asOf, bs.assets.map(StatementGroupResponse::from), bs.liabilities.map(StatementGroupResponse::from),
            bs.funds.map(StatementGroupResponse::from), bs.surplus, bs.totalAssets, bs.totalLiabilities, bs.totalFunds, bs.balanced,
        )
    }
}

data class GroupTotalResponse(val name: String, val amount: BigDecimal)

data class MonthSummaryResponse(
    // "2026-11"
    val month: String,
    val income: List<GroupTotalResponse>,
    val expenditure: List<GroupTotalResponse>,
    val totalIncome: BigDecimal,
    val totalExpenditure: BigDecimal,
    val closingCashAndBank: BigDecimal,
) {
    companion object {
        fun from(m: MonthSummary) = MonthSummaryResponse(
            m.month.toString(),
            m.income.map { GroupTotalResponse(it.name, it.amount) },
            m.expenditure.map { GroupTotalResponse(it.name, it.amount) },
            m.totalIncome, m.totalExpenditure, m.closingCashAndBank,
        )
    }
}

data class FinanceSummaryResponse(val from: LocalDate, val to: LocalDate, val months: List<MonthSummaryResponse>)
