package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.finance.model.AuditAction
import org.devvikram.societymanagement.finance.model.AuditEntityType
import org.devvikram.societymanagement.finance.model.FinanceAccount
import org.devvikram.societymanagement.finance.model.FinanceBooks
import org.devvikram.societymanagement.finance.model.LineEntry
import org.devvikram.societymanagement.finance.model.SystemAccounts
import org.devvikram.societymanagement.finance.model.Voucher
import org.devvikram.societymanagement.finance.model.VoucherStatus
import org.devvikram.societymanagement.finance.model.VoucherType
import org.devvikram.societymanagement.finance.repository.BooksRepository
import org.devvikram.societymanagement.finance.repository.VoucherRepository
import org.devvikram.societymanagement.society.service.SocietyService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// An opening balance in the account's natural sign: positive is the usual side (an asset's debit, a
// fund's credit); negative is the other side (e.g. an overdrawn bank).
data class OpeningEntry(val accountId: Long, val amount: BigDecimal)

data class StartBooksCommand(val startDate: LocalDate, val openings: List<OpeningEntry>)

data class CorrectOpeningsCommand(val startDate: LocalDate?, val openings: List<OpeningEntry>, val reason: String)

data class BookDefaults(
    val cashDepositAccountId: Long?,
    val chequeDepositAccountId: Long?,
    val upiDepositAccountId: Long?,
    val transferDepositAccountId: Long?,
    val defaultPayingAccountId: Long?,
) {
    fun all() = listOf(cashDepositAccountId, chequeDepositAccountId, upiDepositAccountId, transferDepositAccountId, defaultPayingAccountId)

    fun toMap(): Map<String, Any?> = mapOf(
        "cashDepositAccountId" to cashDepositAccountId,
        "chequeDepositAccountId" to chequeDepositAccountId,
        "upiDepositAccountId" to upiDepositAccountId,
        "transferDepositAccountId" to transferDepositAccountId,
        "defaultPayingAccountId" to defaultPayingAccountId,
    )

    companion object {
        fun of(books: FinanceBooks) = BookDefaults(
            books.cashDepositAccountId, books.chequeDepositAccountId, books.upiDepositAccountId,
            books.transferDepositAccountId, books.defaultPayingAccountId,
        )
    }
}

data class BooksState(
    val societyId: Long,
    val books: FinanceBooks?,
    val hasChart: Boolean,
    val opening: Voucher?,
    // Openings can be corrected until the first other voucher exists.
    val openingsEditable: Boolean,
)

// The openings as entered, plus the General fund line that balances them.
data class OpeningsView(
    val startDate: LocalDate,
    val voucher: Voucher,
    val entries: List<OpeningEntry>,
    // Natural sign (credit positive).
    val generalFund: BigDecimal,
)

// Starting a society's books: the opening balance of each account on the start date, as one OPENING
// voucher. The General fund takes whatever makes it balance, so users never have to balance it by hand.
// Member dues and advances are filled from maintenance, not typed in (step 4 of the plan).
@Service
class BooksService(
    private val booksRepository: BooksRepository,
    private val voucherRepository: VoucherRepository,
    private val chartService: ChartService,
    private val voucherWriter: VoucherWriter,
    private val societyService: SocietyService,
    private val audit: FinanceAudit,
) {
    fun state(societyId: Long): BooksState {
        societyService.getById(societyId)
        val books = booksRepository.findBySocietyId(societyId)
        val opening = books?.let { voucherRepository.findOpening(societyId) }
        return BooksState(
            societyId = societyId,
            books = books,
            hasChart = chartService.hasChart(societyId),
            opening = opening,
            openingsEditable = books != null && voucherRepository.countOtherThan(societyId, VoucherType.OPENING) == 0L,
        )
    }

    fun requireBooks(societyId: Long): FinanceBooks =
        booksRepository.findBySocietyId(societyId) ?: throw IllegalStateException("This society's books haven't been started")

    fun openings(societyId: Long): OpeningsView {
        val books = requireBooks(societyId)
        val voucher = voucherRepository.findOpening(societyId) ?: error("Books of society $societyId have no opening voucher")
        val generalFund = chartService.systemAccount(societyId, SystemAccounts.GENERAL_FUND)
        val accounts = chartService.accounts(societyId).associateBy { it.id }
        val entries = voucher.lines.filter { it.accountId != generalFund.id }.map { line ->
            OpeningEntry(line.accountId, accounts.getValue(line.accountId).type.natural(line.debit, line.credit))
        }
        val fund = voucher.lines.filter { it.accountId == generalFund.id }
            .fold(BigDecimal.ZERO) { sum, line -> sum + generalFund.type.natural(line.debit, line.credit) }
        return OpeningsView(books.startDate, voucher, entries, fund)
    }

    @Transactional
    fun start(societyId: Long, command: StartBooksCommand, userId: Long, today: LocalDate): Long {
        societyService.getById(societyId)
        booksRepository.lockSociety(societyId)
        if (booksRepository.findBySocietyId(societyId) != null) throw ConflictException("This society's books are already started")
        require(!command.startDate.isAfter(today)) { "The start date can't be in the future" }
        chartService.ensureChart(societyId, userId)
        // Everything is checked before anything is written.
        val lines = openingLines(societyId, command.openings)
        voucherWriter.checkLines(societyId, lines)

        val books = FinanceBooks(societyId, command.startDate, userId)
        books.cashDepositAccountId = chartService.systemAccount(societyId, SystemAccounts.CASH).id
        books.defaultPayingAccountId = books.cashDepositAccountId
        booksRepository.save(books)
        val voucher = voucherWriter.write(
            societyId,
            VoucherDraft(VoucherType.OPENING, command.startDate, VoucherStatus.POSTED, lines, narration = "Opening balances"),
            userId,
        )
        audit.record(societyId, AuditEntityType.BOOKS, societyId, AuditAction.START, userId,
            newValues = mapOf("startDate" to command.startDate, "openingVoucher" to voucher.number) + linesSnapshot(lines))
        return voucher.id!!
    }

    // Replaces the openings (and optionally the start date) while no other voucher exists.
    @Transactional
    fun correctOpenings(societyId: Long, command: CorrectOpeningsCommand, userId: Long, today: LocalDate) {
        val books = requireBooks(societyId)
        booksRepository.lockSociety(societyId)
        if (voucherRepository.countOtherThan(societyId, VoucherType.OPENING) > 0) {
            throw ConflictException("Openings can't be changed once other entries exist; record a journal entry instead")
        }
        val reason = command.reason.trim()
        require(reason.isNotEmpty()) { "Give a reason for correcting the openings" }
        val startDate = command.startDate ?: books.startDate
        require(!startDate.isAfter(today)) { "The start date can't be in the future" }

        val voucher = voucherRepository.findOpening(societyId) ?: error("Books of society $societyId have no opening voucher")
        val before = mapOf("startDate" to books.startDate) + linesSnapshot(voucher.lines.map { LineEntry(it.accountId, it.debit, it.credit, it.flatId) })
        val lines = openingLines(societyId, command.openings)
        voucherWriter.checkLines(societyId, lines)
        if (startDate != voucher.voucherDate) {
            books.startDate = startDate
            voucher.voucherDate = startDate
            // The opening is the only voucher, so it keeps number 1 unless its financial year changes.
            if (voucherWriter.financialYearOf(societyId, startDate) != voucher.financialYear) voucherWriter.number(voucher)
        }
        voucher.replaceLines(lines)
        voucher.updatedAt = Instant.now()
        voucherRepository.save(voucher)
        booksRepository.save(books)
        audit.record(societyId, AuditEntityType.BOOKS, societyId, AuditAction.UPDATE, userId,
            oldValues = before, newValues = mapOf("startDate" to startDate) + linesSnapshot(lines), reason = reason)
    }

    // Locks the books up to a day (null unlocks). Moving the lock back, or removing it, needs a reason:
    // it reopens a period that may have been audited.
    @Transactional
    fun setLock(societyId: Long, lockedUntil: LocalDate?, reason: String?, userId: Long, today: LocalDate) {
        val books = requireBooks(societyId)
        booksRepository.lockSociety(societyId)
        val current = books.lockedUntil
        if (lockedUntil == current) return
        lockedUntil?.let {
            require(!it.isBefore(books.startDate)) { "The lock date can't be before the books start (${books.startDate})" }
            require(!it.isAfter(today)) { "The books can only be locked up to today" }
        }
        val unlocking = current != null && (lockedUntil == null || lockedUntil.isBefore(current))
        val why = reason?.trim()?.ifEmpty { null }
        require(!unlocking || why != null) { "Give a reason for reopening a locked period" }
        require(why == null || why.length <= 500) { "The reason can be at most 500 characters" }
        books.lockedUntil = lockedUntil
        booksRepository.save(books)
        audit.record(societyId, AuditEntityType.BOOKS, societyId, if (unlocking) AuditAction.UNLOCK else AuditAction.LOCK, userId,
            oldValues = mapOf("lockedUntil" to current), newValues = mapOf("lockedUntil" to lockedUntil), reason = why)
    }

    // Where maintenance payments go per method, and the usual paying account. Each is a cash/bank ledger
    // or null (no default). Replaces all five.
    @Transactional
    fun setDefaults(societyId: Long, defaults: BookDefaults, userId: Long) {
        val books = requireBooks(societyId)
        booksRepository.lockSociety(societyId)
        val money = chartService.moneyAccountIds(societyId)
        defaults.all().filterNotNull().forEach { id ->
            val account = chartService.requirePostable(societyId, id)
            require(id in money) { "${account.name} is not a cash or bank account" }
        }
        val before = BookDefaults.of(books)
        if (before == defaults) return
        books.cashDepositAccountId = defaults.cashDepositAccountId
        books.chequeDepositAccountId = defaults.chequeDepositAccountId
        books.upiDepositAccountId = defaults.upiDepositAccountId
        books.transferDepositAccountId = defaults.transferDepositAccountId
        books.defaultPayingAccountId = defaults.defaultPayingAccountId
        booksRepository.save(books)
        audit.record(societyId, AuditEntityType.MAPPING, societyId, AuditAction.UPDATE, userId,
            oldValues = before.toMap(), newValues = defaults.toMap())
    }

    // Debit/credit lines for the entries, plus a General fund line for the difference.
    private fun openingLines(societyId: Long, openings: List<OpeningEntry>): List<LineEntry> {
        val duplicates = openings.groupBy { it.accountId }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Each account can have one opening balance (repeated: ${duplicates.joinToString()})" }
        val generalFund = chartService.systemAccount(societyId, SystemAccounts.GENERAL_FUND)
        val lines = openings.filter { it.amount.signum() != 0 }.map { entry ->
            require(entry.amount.scale() <= 2) { "Amounts can have at most 2 decimal places" }
            val account = chartService.requirePostable(societyId, entry.accountId)
            require(account.id != generalFund.id) { "The General fund's opening is worked out from the other balances" }
            require(account.systemKey !in SystemAccounts.PER_FLAT) {
                "${account.name} is filled in from maintenance, not entered by hand"
            }
            line(account, entry.amount)
        }
        val difference = lines.fold(BigDecimal.ZERO) { sum, line -> sum + line.debit - line.credit }
        return when (difference.signum()) {
            1 -> lines + LineEntry.credit(generalFund.id!!, difference)
            -1 -> lines + LineEntry.debit(generalFund.id!!, difference.negate())
            else -> lines
        }
    }

    private fun line(account: FinanceAccount, natural: BigDecimal): LineEntry {
        val onNormalSide = natural.signum() > 0
        val amount = natural.abs()
        return if (account.type.debitNormal == onNormalSide) LineEntry.debit(account.id!!, amount)
        else LineEntry.credit(account.id!!, amount)
    }

    private fun linesSnapshot(lines: List<LineEntry>): Map<String, Any?> =
        mapOf("lines" to lines.map { mapOf("accountId" to it.accountId, "debit" to it.debit, "credit" to it.credit) })
}
