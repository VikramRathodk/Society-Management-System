package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.finance.model.AuditAction
import org.devvikram.societymanagement.finance.model.AuditEntityType
import org.devvikram.societymanagement.finance.model.BankLine
import org.devvikram.societymanagement.finance.model.BankLineState
import org.devvikram.societymanagement.finance.model.BankMatch
import org.devvikram.societymanagement.finance.model.BankStatement
import org.devvikram.societymanagement.finance.model.StatementStatus
import org.devvikram.societymanagement.finance.model.Voucher
import org.devvikram.societymanagement.finance.model.VoucherStatus
import org.devvikram.societymanagement.finance.repository.BankMatchRepository
import org.devvikram.societymanagement.finance.repository.BankStatementRepository
import org.devvikram.societymanagement.finance.repository.BookEntryRow
import org.devvikram.societymanagement.finance.repository.BooksRepository
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// A book entry with where it cleared, if it has: against a bank line, or against its pair (a voided
// entry and its reversal that never reached the bank).
data class BookEntryView(val row: BookEntryRow, val bankLineId: Long?, val pairLineId: Long?, val auto: Boolean?)

// A bank line with the book entries it cleared, and the voucher recorded from it (if any).
data class BankLineView(val line: BankLine, val entries: List<BookEntryRow>, val auto: Boolean?, val voucher: Voucher?)

data class Suggestion(val entry: BookEntryRow, val days: Long, val withinWindow: Boolean)

data class Suggestions(
    // Single entries of the same amount, nearest date first.
    val single: List<Suggestion>,
    // Groups of entries that add up to the bank line (cheques deposited together), smallest first.
    val groups: List<List<BookEntryRow>>,
)

data class AutoMatchResult(val matched: Int, val paired: Int)

// The other side of a voucher recorded from a bank line: an income or expense account, or another cash
// or bank account (a transfer). The bank side, date and amount come from the line.
data class LineVoucherCommand(val accountId: Long, val payee: String?, val narration: String?, val reference: String?)

// Matching a bank statement's lines to the bank ledger's entries in the books. Never changes a voucher:
// anything missing from the books is recorded as a normal voucher (with its approval and lock date) and
// then matched. Everything here needs the statement open.
@Service
class ReconciliationService(
    private val bankStatementService: BankStatementService,
    private val bankStatementRepository: BankStatementRepository,
    private val matchRepository: BankMatchRepository,
    private val booksRepository: BooksRepository,
    private val voucherService: VoucherService,
    private val chartService: ChartService,
    private val settings: SocietySettingsService,
    private val audit: FinanceAudit,
) {
    // ---- Reading ----

    // Uncleared: every entry still waiting to clear up to the statement's end, including ones carried over
    // from earlier months. Cleared: the entries this statement cleared.
    fun bookEntries(statementId: Long, cleared: Boolean): List<BookEntryView> {
        val statement = bankStatementService.get(statementId)
        if (!cleared) return matchRepository.uncleared(statement.accountId, statement.periodTo).map { BookEntryView(it, null, null, null) }
        val matches = matchRepository.findByStatement(statementId).associateBy { it.financeLineId }
        return matchRepository.entries(matches.keys).map { row ->
            val match = matches.getValue(row.lineId)
            BookEntryView(row, match.bankLineId, match.pairLineId, match.auto)
        }
    }

    fun lines(statementId: Long, state: BankLineState?): List<BankLineView> {
        bankStatementService.get(statementId)
        val lines = bankStatementRepository.findLines(statementId, state)
        val matches = matchRepository.findByStatement(statementId).filter { it.bankLineId != null }
        val entries = matchRepository.entries(matches.map { it.financeLineId }).associateBy { it.lineId }
        val byLine = matches.groupBy { it.bankLineId!! }
        val vouchers = lines.mapNotNull { it.voucherId }.distinct().associateWith { voucherService.get(it) }
        return lines.map { line ->
            val own = byLine[line.id].orEmpty()
            BankLineView(line, own.mapNotNull { entries[it.financeLineId] }, own.firstOrNull()?.auto, line.voucherId?.let(vouchers::get))
        }
    }

    // What a bank line could be: same amount near its date, or a group adding up to it.
    fun suggestions(statementId: Long, lineId: Long): Suggestions {
        val statement = bankStatementService.get(statementId)
        val line = lineOf(statement, lineId)
        val window = window(statement.societyId)
        val uncleared = matchRepository.uncleared(statement.accountId, statement.periodTo)
        fun days(row: BookEntryRow) = ChronoUnit.DAYS.between(row.date, line.txnDate)
        val single = uncleared.filter { it.net.compareTo(line.net) == 0 }
            .sortedWith(compareBy({ kotlin.math.abs(days(it)) }, { it.date }))
            .take(10)
            .map { Suggestion(it, days(it), kotlin.math.abs(days(it)) <= window) }
        // Deposits reach the bank after they are written up, so groups look back further than ahead.
        val pool = uncleared.filter {
            it.net.signum() == line.net.signum() && it.net.abs() < line.net.abs() &&
                days(it) in -window.toLong()..GROUP_LOOKBACK_DAYS
        }.sortedBy { kotlin.math.abs(days(it)) }.take(GROUP_POOL)
        return Suggestions(single, groupsAddingUpTo(pool, line.net.abs()))
    }

    // ---- Matching ----

    // The bank line clears these entries; they must be waiting to clear on this bank account, dated within
    // the statement, and add up to the line exactly.
    @Transactional
    fun match(statementId: Long, lineId: Long, bookLineIds: List<Long>, userId: Long) {
        val statement = openStatement(statementId)
        val line = freeLine(statement, lineId)
        require(bookLineIds.isNotEmpty()) { "Choose the entries in the books this bank line is" }
        require(bookLineIds.distinct().size == bookLineIds.size) { "An entry is chosen twice" }
        val waiting = matchRepository.uncleared(statement.accountId, statement.periodTo).associateBy { it.lineId }
        val rows = bookLineIds.map { id ->
            waiting[id] ?: throw ConflictException(
                "That entry isn't waiting to clear on this bank account: already cleared, not in the books, " +
                    "or dated after ${statement.periodTo}"
            )
        }
        val total = rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.net }
        require(total.compareTo(line.net) == 0) {
            "The entries come to ${signed(total)} but the bank line is ${signed(line.net)}"
        }
        saveMatch(statement, line, rows, auto = false, userId)
    }

    @Transactional
    fun unmatch(statementId: Long, lineId: Long, userId: Long) {
        val statement = openStatement(statementId)
        val line = lineOf(statement, lineId)
        if (line.state != BankLineState.MATCHED) throw ConflictException("This bank line isn't matched")
        val matches = matchRepository.findByBankLine(lineId)
        val numbers = matchRepository.entries(matches.map { it.financeLineId }).map { it.number }
        matchRepository.deleteAll(matches)
        line.state = BankLineState.UNMATCHED
        matchRepository.saveLine(line)
        audit.record(statement.societyId, AuditEntityType.RECONCILIATION, statementId, AuditAction.UNMATCH, userId,
            oldValues = mapOf("bankLine" to lineId, "entries" to numbers))
    }

    // Two entries that cancel each other and never reached the bank (an entry and its reversal; a
    // maintenance payment and its void) clear together, without a bank line.
    @Transactional
    fun pair(statementId: Long, firstLineId: Long, secondLineId: Long, userId: Long) {
        val statement = openStatement(statementId)
        require(firstLineId != secondLineId) { "Choose two different entries" }
        val waiting = matchRepository.uncleared(statement.accountId, statement.periodTo).associateBy { it.lineId }
        val first = waiting[firstLineId] ?: throw ConflictException("The first entry isn't waiting to clear up to ${statement.periodTo}")
        val second = waiting[secondLineId] ?: throw ConflictException("The second entry isn't waiting to clear up to ${statement.periodTo}")
        require((first.net + second.net).signum() == 0) { "Only an entry and one that cancels it exactly can clear together" }
        savePair(statement, first, second, auto = false, userId)
    }

    @Transactional
    fun unpair(statementId: Long, bookLineId: Long, userId: Long) {
        val statement = openStatement(statementId)
        val match = matchRepository.findByFinanceLines(listOf(bookLineId)).singleOrNull()
            ?.takeIf { it.statementId == statementId && it.pairLineId != null }
            ?: throw ConflictException("That entry isn't cleared against another entry in this statement")
        val both = matchRepository.findByFinanceLines(listOf(bookLineId, match.pairLineId!!))
        val numbers = matchRepository.entries(both.map { it.financeLineId }).map { it.number }
        matchRepository.deleteAll(both)
        audit.record(statement.societyId, AuditEntityType.RECONCILIATION, statementId, AuditAction.UNMATCH, userId,
            oldValues = mapOf("pair" to numbers))
    }

    // A bank error the bank itself reversed, or a line that isn't this account's: it needs no entry in the
    // books, only a reason.
    @Transactional
    fun exclude(statementId: Long, lineId: Long, reason: String, userId: Long) {
        val statement = openStatement(statementId)
        val line = freeLine(statement, lineId)
        val why = reason.trim().take(500).ifEmpty { throw IllegalArgumentException("Say why this bank line needs no entry") }
        line.state = BankLineState.EXCLUDED
        line.excludeReason = why
        matchRepository.saveLine(line)
        audit.record(statement.societyId, AuditEntityType.RECONCILIATION, statementId, AuditAction.EXCLUDE, userId,
            newValues = mapOf("bankLine" to lineId, "description" to line.description, "amount" to signed(line.net)), reason = why)
    }

    @Transactional
    fun include(statementId: Long, lineId: Long, userId: Long) {
        val statement = openStatement(statementId)
        val line = lineOf(statement, lineId)
        if (line.state != BankLineState.EXCLUDED) throw ConflictException("This bank line isn't set aside")
        line.state = BankLineState.UNMATCHED
        line.excludeReason = null
        matchRepository.saveLine(line)
        audit.record(statement.societyId, AuditEntityType.RECONCILIATION, statementId, AuditAction.INCLUDE, userId,
            newValues = mapOf("bankLine" to lineId))
    }

    // Records what the books are missing (bank charges, interest, a transfer) as a normal voucher dated on
    // the bank line: money in is a receipt, money out a payment, another cash/bank account a transfer.
    // A posted voucher clears the line at once; one waiting for approval clears it when approved.
    @Transactional
    fun recordVoucher(statementId: Long, lineId: Long, command: LineVoucherCommand, userId: Long, today: LocalDate): Long {
        val statement = openStatement(statementId)
        val line = freeLine(statement, lineId)
        val bank = statement.accountId
        val amount = line.net.abs()
        val moneyIn = line.net.signum() > 0
        val description = line.description.take(500)
        val reference = command.reference?.trim()?.ifEmpty { null } ?: line.reference
        val narration = command.narration?.trim()?.ifEmpty { null } ?: description
        val transfer = command.accountId in chartService.moneyAccountIds(statement.societyId)
        val voucherId = when {
            transfer -> voucherService.recordContra(
                statement.societyId,
                ContraCommand(
                    date = line.txnDate,
                    fromAccountId = if (moneyIn) command.accountId else bank,
                    toAccountId = if (moneyIn) bank else command.accountId,
                    amount = amount,
                    reference = reference,
                    narration = narration,
                ),
                userId, today,
            )
            moneyIn -> voucherService.recordReceipt(
                statement.societyId,
                ReceiptCommand(line.txnDate, bank, listOf(AmountLine(command.accountId, amount)),
                    payee = command.payee?.trim()?.ifEmpty { null }, reference = reference, narration = narration),
                userId, today,
            )
            else -> voucherService.recordPayment(
                statement.societyId,
                PaymentCommand(line.txnDate, bank, listOf(AmountLine(command.accountId, amount)),
                    payee = command.payee?.trim()?.ifEmpty { null } ?: description.take(200), reference = reference, narration = narration),
                userId, today,
            )
        }
        line.voucherId = voucherId
        matchRepository.saveLine(line)
        val voucher = voucherService.get(voucherId)
        if (voucher.status == VoucherStatus.POSTED) {
            saveMatch(statement, line, matchRepository.entriesOfVouchers(bank, listOf(voucherId)), auto = false, userId)
        }
        return voucherId
    }

    // Clears what is certain and leaves the rest as suggestions: an entry and its reversal that never
    // reached the bank pair up; a bank line and an entry of the same amount within the matching window
    // match when each is the other's only candidate.
    @Transactional
    fun autoMatch(statementId: Long, userId: Long): AutoMatchResult {
        val statement = openStatement(statementId)
        var uncleared = matchRepository.uncleared(statement.accountId, statement.periodTo)

        var paired = 0
        val byVoucher = uncleared.groupBy { it.voucherId }
        uncleared.filter { it.status == VoucherStatus.VOID }.forEach { original ->
            val reversal = uncleared.firstOrNull { it.reversesVoucherId == original.voucherId && (it.net + original.net).signum() == 0 }
            if (reversal != null && byVoucher[original.voucherId].orEmpty().size == 1) {
                savePair(statement, original, reversal, auto = true, userId, audited = false)
                paired++
            }
        }
        if (paired > 0) uncleared = matchRepository.uncleared(statement.accountId, statement.periodTo)

        val window = window(statement.societyId)
        val lines = bankStatementRepository.findLines(statementId, BankLineState.UNMATCHED).filter { pendingVoucher(it) == null }
        fun close(row: BookEntryRow, line: BankLine) =
            row.net.compareTo(line.net) == 0 && kotlin.math.abs(ChronoUnit.DAYS.between(row.date, line.txnDate)) <= window
        val candidates = lines.associateWith { line -> uncleared.filter { close(it, line) } }
        val claims = uncleared.associateWith { row -> lines.count { close(row, it) } }
        var matched = 0
        candidates.forEach { (line, rows) ->
            val row = rows.singleOrNull() ?: return@forEach
            if (claims.getValue(row) != 1) return@forEach
            saveMatch(statement, line, listOf(row), auto = true, userId, audited = false)
            matched++
        }
        if (matched + paired > 0) {
            audit.record(statement.societyId, AuditEntityType.RECONCILIATION, statementId, AuditAction.MATCH, userId,
                newValues = mapOf("auto" to true, "matched" to matched, "paired" to paired))
        }
        return AutoMatchResult(matched, paired)
    }

    @Transactional
    fun importAndMatch(statementId: Long, data: ByteArray, filename: String?, userId: Long): Pair<ImportResult, AutoMatchResult> {
        val result = bankStatementService.import(statementId, data, filename, userId)
        return result to autoMatch(statementId, userId)
    }

    // A voucher recorded from a bank line was approved (clear the line) or rejected (free it).
    @EventListener
    fun onDecided(event: VoucherDecided) {
        val lines = matchRepository.findLinesByVoucher(event.voucherId)
        if (lines.isEmpty()) return
        val voucher = voucherService.get(event.voucherId)
        lines.forEach { line ->
            val statement = bankStatementService.get(line.statementId)
            if (statement.status != StatementStatus.OPEN || line.state != BankLineState.UNMATCHED) return@forEach
            if (!event.approved) {
                line.voucherId = null
                matchRepository.saveLine(line)
                return@forEach
            }
            val rows = matchRepository.entriesOfVouchers(line.accountId, listOf(voucher.id!!))
            val waiting = matchRepository.uncleared(line.accountId, statement.periodTo).map { it.lineId }.toSet()
            if (rows.isNotEmpty() && rows.all { it.lineId in waiting } &&
                rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.net }.compareTo(line.net) == 0
            ) {
                saveMatch(statement, line, rows, auto = false, voucher.decidedBy ?: voucher.createdBy)
            }
        }
    }

    // ---- helpers ----

    private fun saveMatch(statement: BankStatement, line: BankLine, rows: List<BookEntryRow>, auto: Boolean, userId: Long, audited: Boolean = true) {
        matchRepository.saveAll(rows.map { BankMatch(statement.id!!, line.id, it.lineId, null, auto, userId) })
        line.state = BankLineState.MATCHED
        matchRepository.saveLine(line)
        if (audited) {
            audit.record(statement.societyId, AuditEntityType.RECONCILIATION, statement.id, AuditAction.MATCH, userId,
                newValues = mapOf("bankLine" to line.id, "amount" to signed(line.net), "entries" to rows.map { it.number }))
        }
    }

    private fun savePair(statement: BankStatement, first: BookEntryRow, second: BookEntryRow, auto: Boolean, userId: Long, audited: Boolean = true) {
        matchRepository.saveAll(listOf(
            BankMatch(statement.id!!, null, first.lineId, second.lineId, auto, userId),
            BankMatch(statement.id!!, null, second.lineId, first.lineId, auto, userId),
        ))
        if (audited) {
            audit.record(statement.societyId, AuditEntityType.RECONCILIATION, statement.id, AuditAction.MATCH, userId,
                newValues = mapOf("pair" to listOf(first.number, second.number)))
        }
    }

    private fun openStatement(statementId: Long): BankStatement {
        val statement = bankStatementService.requireOpen(statementId)
        booksRepository.lockSociety(statement.societyId)
        return statement
    }

    private fun lineOf(statement: BankStatement, lineId: Long): BankLine =
        matchRepository.findLine(lineId)?.takeIf { it.statementId == statement.id }
            ?: throw NoSuchElementException("Bank line $lineId is not on this statement")

    // A line that can still be matched, set aside or have a voucher recorded from it.
    private fun freeLine(statement: BankStatement, lineId: Long): BankLine {
        val line = lineOf(statement, lineId)
        when (line.state) {
            BankLineState.MATCHED -> throw ConflictException("This bank line is already matched; unmatch it first")
            BankLineState.EXCLUDED -> throw ConflictException("This bank line is set aside; bring it back first")
            BankLineState.UNMATCHED -> {}
        }
        pendingVoucher(line)?.let { throw ConflictException("${it.number}, recorded from this line, is waiting for approval") }
        return line
    }

    private fun pendingVoucher(line: BankLine): Voucher? =
        line.voucherId?.let { voucherService.get(it) }?.takeIf { it.status == VoucherStatus.PENDING_APPROVAL }

    private fun window(societyId: Long): Int = settings.integer(societyId, SocietySettingKeys.RECONCILIATION_MATCH_DAYS).toInt()

    private fun signed(amount: BigDecimal): String = (if (amount.signum() < 0) "−₹" else "₹") + amount.abs().toPlainString()

    // Sets of 2 to 8 entries adding up to `target` exactly, fewest entries first. `pool` is small (≤ 20)
    // and every amount is positive here, so a bounded search is enough.
    private fun groupsAddingUpTo(pool: List<BookEntryRow>, target: BigDecimal): List<List<BookEntryRow>> {
        val items = pool.sortedByDescending { it.net.abs() }
        val found = mutableListOf<List<BookEntryRow>>()
        var steps = 0
        for (size in 2..minOf(MAX_GROUP, items.size)) {
            fun search(start: Int, left: BigDecimal, chosen: MutableList<BookEntryRow>) {
                if (found.size >= MAX_GROUPS || ++steps > MAX_STEPS) return
                if (chosen.size == size) {
                    if (left.signum() == 0) found.add(chosen.sortedBy { it.date })
                    return
                }
                for (i in start until items.size) {
                    val amount = items[i].net.abs()
                    if (amount > left) continue
                    chosen.add(items[i])
                    search(i + 1, left - amount, chosen)
                    chosen.removeAt(chosen.size - 1)
                    if (found.size >= MAX_GROUPS) return
                }
            }
            search(0, target, mutableListOf())
            if (found.size >= MAX_GROUPS) break
        }
        return found
    }

    companion object {
        const val GROUP_LOOKBACK_DAYS = 30L
        const val GROUP_POOL = 20
        const val MAX_GROUP = 8
        const val MAX_GROUPS = 5
        const val MAX_STEPS = 300_000
    }
}
