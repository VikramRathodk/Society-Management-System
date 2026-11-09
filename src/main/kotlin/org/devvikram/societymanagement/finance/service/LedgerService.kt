package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.finance.model.FinanceAccount
import org.devvikram.societymanagement.finance.model.SystemAccounts
import org.devvikram.societymanagement.finance.repository.LedgerRow
import org.devvikram.societymanagement.finance.repository.VoucherRepository
import org.devvikram.societymanagement.society.repository.FlatRepository
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

data class LedgerLine(val row: LedgerRow, val flatLabel: String?, val balance: BigDecimal)

// One account's movements over a period; balances in the account's natural sign.
data class LedgerView(
    val account: FinanceAccount,
    val flatId: Long?,
    val flatLabel: String?,
    val from: LocalDate,
    val to: LocalDate,
    val opening: BigDecimal,
    val lines: List<LedgerLine>,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
    val closing: BigDecimal,
)

@Service
class LedgerService(
    private val voucherRepository: VoucherRepository,
    private val chartService: ChartService,
    private val booksService: BooksService,
    private val flatRepository: FlatRepository,
    private val settings: SocietySettingsService,
) {
    // Defaults: from the start of the current financial year (not before the books start) to today.
    // `flatId` narrows a member sub-ledger to one flat (the member ledger).
    @Transactional(readOnly = true)
    fun ledger(accountId: Long, flatId: Long?, from: LocalDate?, to: LocalDate?, today: LocalDate): LedgerView {
        val account = chartService.get(accountId)
        val books = booksService.requireBooks(account.societyId)
        require(!account.isGroup) { "${account.name} is a group; open one of the accounts under it" }
        val flats = flatRepository.findBySocietyId(account.societyId).associate { it.id!! to "${it.wing.name}-${it.flatNumber}" }
        if (flatId != null) {
            require(account.systemKey in SystemAccounts.PER_FLAT) { "Only member accounts can be narrowed to a flat" }
            require(flatId in flats) { "Flat $flatId is not in this society" }
        }
        val startMonth = settings.integer(account.societyId, SocietySettingKeys.FINANCIAL_YEAR_START_MONTH).toInt()
        val end = to ?: today
        val start = from ?: maxOf(FinancialYears.startOf(end, startMonth), books.startDate)
        require(!start.isAfter(end)) { "'from' must not be after 'to'" }

        val before = voucherRepository.totalsBefore(accountId, flatId, start)
        val opening = account.type.natural(before.debit, before.credit).setScale(2)
        var running = opening
        var debit = BigDecimal.ZERO.setScale(2)
        var credit = BigDecimal.ZERO.setScale(2)
        val lines = voucherRepository.ledger(accountId, flatId, start, end).map { row ->
            running += account.type.natural(row.debit, row.credit)
            debit += row.debit
            credit += row.credit
            LedgerLine(row, row.flatId?.let(flats::get), running)
        }
        return LedgerView(account, flatId, flatId?.let(flats::get), start, end, opening, lines, debit, credit, running)
    }
}
