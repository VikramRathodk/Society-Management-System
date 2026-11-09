package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.finance.model.LineEntry
import org.devvikram.societymanagement.finance.model.SystemAccounts
import org.devvikram.societymanagement.finance.model.Voucher
import org.devvikram.societymanagement.finance.model.VoucherStatus
import org.devvikram.societymanagement.finance.model.VoucherType
import org.devvikram.societymanagement.finance.repository.VoucherRepository
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

// What a new voucher says, apart from its number.
data class VoucherDraft(
    val type: VoucherType,
    val date: LocalDate,
    val status: VoucherStatus,
    val lines: List<LineEntry>,
    val narration: String? = null,
    val payee: String? = null,
    val reference: String? = null,
    val flatId: Long? = null,
    // Reversals may post to ledgers deactivated since the original was written.
    val allowInactive: Boolean = false,
)

// Checks lines and numbers vouchers. Callers hold the society lock (BooksRepository.lockSociety), so the
// next number is safe to take: RV/2026-27/0001, restarting per type and financial year.
@Component
class VoucherWriter(
    private val voucherRepository: VoucherRepository,
    private val chartService: ChartService,
    private val settings: SocietySettingsService,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun write(societyId: Long, draft: VoucherDraft, createdBy: Long): Voucher {
        checkLines(societyId, draft.lines, draft.allowInactive)
        val voucher = Voucher(
            societyId = societyId,
            type = draft.type,
            financialYear = "",
            seq = 0,
            number = "",
            voucherDate = draft.date,
            status = draft.status,
            createdBy = createdBy,
            narration = draft.narration,
            payee = draft.payee,
            reference = draft.reference,
            flatId = draft.flatId,
        )
        number(voucher)
        voucher.replaceLines(draft.lines)
        return voucherRepository.save(voucher)
    }

    // Gives the voucher the next number of its type in the financial year of its date.
    @Transactional(propagation = Propagation.MANDATORY)
    fun number(voucher: Voucher) {
        val year = financialYearOf(voucher.societyId, voucher.voucherDate)
        val seq = (voucherRepository.maxSeq(voucher.societyId, voucher.type, year) ?: 0) + 1
        voucher.financialYear = year
        voucher.seq = seq
        voucher.number = "${voucher.type.prefix}/$year/${"%04d".format(seq)}"
    }

    fun financialYearOf(societyId: Long, date: LocalDate): String =
        FinancialYears.labelOf(date, settings.integer(societyId, SocietySettingKeys.FINANCIAL_YEAR_START_MONTH).toInt())

    // Lines balance, post to active ledgers of this society, and name a flat exactly on member sub-ledgers.
    fun checkLines(societyId: Long, lines: List<LineEntry>, allowInactive: Boolean = false) {
        val debit = lines.fold(BigDecimal.ZERO) { sum, line -> sum + line.debit }
        val credit = lines.fold(BigDecimal.ZERO) { sum, line -> sum + line.credit }
        require(debit.compareTo(credit) == 0) { "Debits ($debit) and credits ($credit) must be equal" }
        lines.forEach { line ->
            require(line.debit.scale() <= 2 && line.credit.scale() <= 2) { "Amounts can have at most 2 decimal places" }
            require(line.debit < MAX_AMOUNT && line.credit < MAX_AMOUNT) { "Amount is too large" }
            val account = chartService.requirePostable(societyId, line.accountId, mustBeActive = !allowInactive)
            val perFlat = account.systemKey in SystemAccounts.PER_FLAT
            require(perFlat == (line.flatId != null)) {
                if (perFlat) "${account.name} needs a flat on every entry" else "Only member accounts take a flat"
            }
        }
    }

    private companion object {
        val MAX_AMOUNT = BigDecimal("1000000000000")
    }
}
