package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.finance.model.AuditAction
import org.devvikram.societymanagement.finance.model.AuditEntityType
import org.devvikram.societymanagement.finance.model.FinanceBooks
import org.devvikram.societymanagement.finance.model.LineEntry
import org.devvikram.societymanagement.finance.model.SystemAccounts
import org.devvikram.societymanagement.finance.model.Voucher
import org.devvikram.societymanagement.finance.model.VoucherStatus
import org.devvikram.societymanagement.finance.model.VoucherType
import org.devvikram.societymanagement.finance.repository.BooksRepository
import org.devvikram.societymanagement.finance.repository.VoucherRepository
import org.devvikram.societymanagement.finance.repository.VoucherSearch
import org.devvikram.societymanagement.society.repository.FlatRepository
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.devvikram.societymanagement.user.repository.UserRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// One account and amount on a receipt (credited) or a payment (debited).
data class AmountLine(val accountId: Long, val amount: BigDecimal)

// The cash or bank ledger the money came into; the accounts it is income (or a deposit, ...) for.
data class ReceiptCommand(
    val date: LocalDate,
    val depositAccountId: Long,
    val lines: List<AmountLine>,
    val payee: String? = null,
    val flatId: Long? = null,
    val reference: String? = null,
    val narration: String? = null,
)

// The cash or bank ledger the money was paid from; the expense (or other) accounts it was paid for.
data class PaymentCommand(
    val date: LocalDate,
    val payingAccountId: Long,
    val lines: List<AmountLine>,
    val payee: String,
    val flatId: Long? = null,
    val reference: String? = null,
    val narration: String? = null,
)

// Money moved between two cash/bank ledgers (a deposit of cash into the bank, a withdrawal, a transfer).
data class ContraCommand(
    val date: LocalDate,
    val fromAccountId: Long,
    val toAccountId: Long,
    val amount: BigDecimal,
    val reference: String? = null,
    val narration: String? = null,
)

data class JournalLine(val accountId: Long, val debit: BigDecimal, val credit: BigDecimal)

data class JournalCommand(
    val date: LocalDate,
    val lines: List<JournalLine>,
    val narration: String,
    val reference: String? = null,
)

// Vouchers entered by hand: receipts, payments, contras and journals.
//  - Payments and journals above `financeApprovalAbove` wait for a second person (never their maker).
//  - Posted vouchers are never edited: voiding books a linked reversal dated today.
//  - Nothing is dated before the books start, in the future, or on/before the lock date.
//  - Member sub-ledgers (dues, advances) are maintenance's; hand-made vouchers can't touch them.
// Every write takes the society lock (shared with maintenance), so numbers never clash.
@Service
class VoucherService(
    private val voucherRepository: VoucherRepository,
    private val booksRepository: BooksRepository,
    private val booksService: BooksService,
    private val chartService: ChartService,
    private val voucherWriter: VoucherWriter,
    private val flatRepository: FlatRepository,
    private val userRepository: UserRepository,
    private val settings: SocietySettingsService,
    private val audit: FinanceAudit,
    private val events: ApplicationEventPublisher,
) {
    fun get(id: Long): Voucher = voucherRepository.findById(id) ?: throw NoSuchElementException("Voucher $id not found")

    fun search(search: VoucherSearch): List<Voucher> = voucherRepository.search(search)

    fun reversalOf(voucherId: Long): Voucher? = voucherRepository.findReversalOf(voucherId)

    // ---- Create ----

    @Transactional
    fun recordReceipt(societyId: Long, command: ReceiptCommand, userId: Long, today: LocalDate): Long {
        val books = open(societyId, command.date, today)
        val money = requireMoney(societyId, command.depositAccountId)
        val credits = amountLines(societyId, command.lines).map { LineEntry.credit(it.accountId, it.amount) }
        val lines = listOf(LineEntry.debit(money, total(command.lines))) + credits
        return create(books, VoucherType.RECEIPT, command.date, lines, userId, Texts(command.payee, command.reference, command.narration), command.flatId)
    }

    @Transactional
    fun recordPayment(societyId: Long, command: PaymentCommand, userId: Long, today: LocalDate): Long {
        val books = open(societyId, command.date, today)
        val lines = paymentLines(societyId, command)
        return create(books, VoucherType.PAYMENT, command.date, lines, userId, Texts(requirePayee(command.payee), command.reference, command.narration), command.flatId)
    }

    @Transactional
    fun recordContra(societyId: Long, command: ContraCommand, userId: Long, today: LocalDate): Long {
        val books = open(societyId, command.date, today)
        val from = requireMoney(societyId, command.fromAccountId)
        val to = requireMoney(societyId, command.toAccountId)
        require(from != to) { "Choose two different accounts" }
        requireAmount(command.amount)
        val lines = listOf(LineEntry.debit(to, command.amount), LineEntry.credit(from, command.amount))
        return create(books, VoucherType.CONTRA, command.date, lines, userId, Texts(null, command.reference, command.narration), null)
    }

    @Transactional
    fun recordJournal(societyId: Long, command: JournalCommand, userId: Long, today: LocalDate): Long {
        val books = open(societyId, command.date, today)
        val lines = journalLines(societyId, command.lines)
        return create(books, VoucherType.JOURNAL, command.date, lines, userId, Texts(null, command.reference, requireNarration(command.narration)), null)
    }

    // ---- Edit or withdraw while waiting (maker only) ----

    @Transactional
    fun updatePayment(id: Long, command: PaymentCommand, userId: Long, today: LocalDate) {
        val voucher = pendingOfMaker(id, VoucherType.PAYMENT, userId)
        replace(voucher, command.date, today, paymentLines(voucher.societyId, command),
            Texts(requirePayee(command.payee), command.reference, command.narration), command.flatId, userId)
    }

    @Transactional
    fun updateJournal(id: Long, command: JournalCommand, userId: Long, today: LocalDate) {
        val voucher = pendingOfMaker(id, VoucherType.JOURNAL, userId)
        replace(voucher, command.date, today, journalLines(voucher.societyId, command.lines),
            Texts(null, command.reference, requireNarration(command.narration)), null, userId)
    }

    // The maker takes back a voucher that is still waiting. It stays (REJECTED) so its number isn't lost.
    @Transactional
    fun withdraw(id: Long, userId: Long, reason: String?) {
        val voucher = pendingOfMaker(id, null, userId)
        decide(voucher, VoucherStatus.REJECTED, userId, "Withdrawn by the maker" + (clean(reason, 400)?.let { ": $it" } ?: ""))
        audit.record(voucher.societyId, AuditEntityType.VOUCHER, id, AuditAction.REJECT, userId,
            newValues = mapOf("status" to VoucherStatus.REJECTED, "withdrawn" to true), reason = voucher.reason)
    }

    // ---- Approve / reject (anyone holding the permission except the maker) ----

    @Transactional
    fun approve(id: Long, userId: Long, today: LocalDate) {
        val voucher = pendingForApprover(id, userId)
        val books = booksService.requireBooks(voucher.societyId)
        requireOpenDate(books, voucher.voucherDate, today)
        voucherWriter.checkLines(voucher.societyId, voucher.lines.map { LineEntry(it.accountId, it.debit, it.credit, it.flatId) })
        decide(voucher, VoucherStatus.POSTED, userId, null)
        audit.record(voucher.societyId, AuditEntityType.VOUCHER, id, AuditAction.APPROVE, userId,
            newValues = mapOf("status" to VoucherStatus.POSTED))
        events.publishEvent(VoucherDecided(id, voucher.createdBy, voucher.number, approved = true, reason = null))
    }

    @Transactional
    fun reject(id: Long, userId: Long, reason: String) {
        val voucher = pendingForApprover(id, userId)
        val why = clean(reason, 500) ?: throw IllegalArgumentException("Give a reason for rejecting")
        decide(voucher, VoucherStatus.REJECTED, userId, why)
        audit.record(voucher.societyId, AuditEntityType.VOUCHER, id, AuditAction.REJECT, userId,
            newValues = mapOf("status" to VoucherStatus.REJECTED), reason = why)
        events.publishEvent(VoucherDecided(id, voucher.createdBy, voucher.number, approved = false, reason = why))
    }

    // ---- Void: a linked reversal dated today; the original stays in the books, marked VOID ----

    @Transactional
    fun void(id: Long, userId: Long, reason: String, today: LocalDate): Long {
        val voucher = get(id)
        booksRepository.lockSociety(voucher.societyId)
        val why = clean(reason, 500) ?: throw IllegalArgumentException("Give a reason for voiding")
        require(voucher.type in MANUAL) {
            if (voucher.type == VoucherType.OPENING) "Opening balances are corrected, not voided"
            else "This entry came from maintenance; undo it there"
        }
        if (voucher.reversesVoucherId != null) throw ConflictException("${voucher.number} is itself a reversal and can't be voided")
        if (voucher.status != VoucherStatus.POSTED) throw ConflictException("Only posted entries can be voided (${voucher.number} is ${voucher.status})")
        val books = booksService.requireBooks(voucher.societyId)
        requireOpenDate(books, today, today)

        val reversal = voucherWriter.write(
            voucher.societyId,
            VoucherDraft(
                type = voucher.type,
                date = today,
                status = VoucherStatus.POSTED,
                lines = voucher.lines.map { LineEntry(it.accountId, it.credit, it.debit, it.flatId) },
                narration = "Reversal of ${voucher.number}: $why",
                payee = voucher.payee,
                reference = voucher.reference,
                flatId = voucher.flatId,
                allowInactive = true,
            ),
            userId,
        )
        reversal.reversesVoucherId = voucher.id
        voucherRepository.save(reversal)
        voucher.status = VoucherStatus.VOID
        voucher.voidedAt = Instant.now()
        voucher.voidedBy = userId
        voucher.reason = why
        voucherRepository.save(voucher)
        audit.record(voucher.societyId, AuditEntityType.VOUCHER, id, AuditAction.VOID, userId,
            oldValues = mapOf("status" to VoucherStatus.POSTED),
            newValues = mapOf("status" to VoucherStatus.VOID, "reversal" to reversal.number), reason = why)
        return reversal.id!!
    }

    // ---- helpers ----

    private data class Texts(val payee: String?, val reference: String?, val narration: String?)

    private fun open(societyId: Long, date: LocalDate, today: LocalDate): FinanceBooks {
        booksRepository.lockSociety(societyId)
        val books = booksService.requireBooks(societyId)
        requireOpenDate(books, date, today)
        return books
    }

    private fun create(
        books: FinanceBooks, type: VoucherType, date: LocalDate, lines: List<LineEntry>, userId: Long, texts: Texts, flatId: Long?,
    ): Long {
        val societyId = books.societyId
        flatId?.let { requireFlat(societyId, it) }
        val amount = total(lines)
        val needsApproval = type in APPROVABLE && approvalLimit(societyId).let { it > BigDecimal.ZERO && amount > it }
        val voucher = voucherWriter.write(
            societyId,
            VoucherDraft(
                type = type,
                date = date,
                status = if (needsApproval) VoucherStatus.PENDING_APPROVAL else VoucherStatus.POSTED,
                lines = lines,
                narration = clean(texts.narration, 500),
                payee = clean(texts.payee, 200),
                reference = clean(texts.reference, 100),
                flatId = flatId,
            ),
            userId,
        )
        audit.record(societyId, AuditEntityType.VOUCHER, voucher.id, AuditAction.CREATE, userId, newValues = snapshot(voucher))
        if (needsApproval) {
            val maker = userRepository.findById(userId)?.name ?: "Someone"
            events.publishEvent(VoucherNeedsApproval(voucher.id!!, societyId, voucher.number, voucher.amount, voucher.payee, userId, maker))
        }
        return voucher.id!!
    }

    private fun replace(voucher: Voucher, date: LocalDate, today: LocalDate, lines: List<LineEntry>, texts: Texts, flatId: Long?, userId: Long) {
        val books = open(voucher.societyId, date, today)
        require(voucherWriter.financialYearOf(books.societyId, date) == voucher.financialYear) {
            "A waiting entry can't move to another financial year; withdraw it and record a new one"
        }
        flatId?.let { requireFlat(voucher.societyId, it) }
        voucherWriter.checkLines(voucher.societyId, lines)
        val before = snapshot(voucher)
        voucher.voucherDate = date
        voucher.replaceLines(lines)
        voucher.payee = clean(texts.payee, 200)
        voucher.reference = clean(texts.reference, 100)
        voucher.narration = clean(texts.narration, 500)
        voucher.flatId = flatId
        voucher.updatedAt = Instant.now()
        voucherRepository.save(voucher)
        val after = snapshot(voucher)
        audit.record(voucher.societyId, AuditEntityType.VOUCHER, voucher.id, AuditAction.UPDATE, userId,
            oldValues = before.filter { (k, v) -> after[k] != v }, newValues = after.filter { (k, v) -> before[k] != v })
    }

    private fun decide(voucher: Voucher, status: VoucherStatus, userId: Long, reason: String?) {
        voucher.status = status
        voucher.decidedAt = Instant.now()
        voucher.decidedBy = userId
        voucher.reason = reason
        voucherRepository.save(voucher)
    }

    private fun pendingOfMaker(id: Long, type: VoucherType?, userId: Long): Voucher {
        val voucher = get(id)
        booksRepository.lockSociety(voucher.societyId)
        if (type != null) require(voucher.type == type) { "${voucher.number} is a ${voucher.type.name.lowercase()}, not a ${type.name.lowercase()}" }
        if (voucher.status != VoucherStatus.PENDING_APPROVAL) throw ConflictException("${voucher.number} is no longer waiting for approval")
        if (voucher.createdBy != userId) throw AccessDeniedException("Only the person who recorded ${voucher.number} can change it")
        return voucher
    }

    private fun pendingForApprover(id: Long, userId: Long): Voucher {
        val voucher = get(id)
        booksRepository.lockSociety(voucher.societyId)
        if (voucher.status != VoucherStatus.PENDING_APPROVAL) throw ConflictException("${voucher.number} is not waiting for approval")
        if (voucher.createdBy == userId) throw AccessDeniedException("You recorded ${voucher.number}, so someone else has to approve it")
        return voucher
    }

    private fun requireOpenDate(books: FinanceBooks, date: LocalDate, today: LocalDate) {
        require(!date.isBefore(books.startDate)) { "The books start on ${books.startDate}; entries can't be dated earlier" }
        require(!date.isAfter(today)) { "Entries can't be dated in the future" }
        books.lockedUntil?.let { require(date.isAfter(it)) { "The books are locked up to $it" } }
    }

    private fun paymentLines(societyId: Long, command: PaymentCommand): List<LineEntry> {
        val money = requireMoney(societyId, command.payingAccountId)
        val debits = amountLines(societyId, command.lines).map { LineEntry.debit(it.accountId, it.amount) }
        return debits + LineEntry.credit(money, total(command.lines))
    }

    // Receipt and payment lines: ordinary ledgers, not cash/bank (that's a contra) and not member accounts.
    private fun amountLines(societyId: Long, lines: List<AmountLine>): List<AmountLine> {
        require(lines.isNotEmpty()) { "Add at least one account" }
        val money = chartService.moneyAccountIds(societyId)
        lines.forEach { line ->
            requireAmount(line.amount)
            val account = chartService.requirePostable(societyId, line.accountId)
            require(account.id !in money) { "${account.name} is a cash or bank account; record a transfer instead" }
            requireNotMember(account.systemKey, account.name)
        }
        return lines
    }

    private fun journalLines(societyId: Long, lines: List<JournalLine>): List<LineEntry> {
        require(lines.size >= 2) { "A journal entry needs at least two lines" }
        return lines.map { line ->
            val account = chartService.requirePostable(societyId, line.accountId)
            requireNotMember(account.systemKey, account.name)
            require(line.debit.signum() >= 0 && line.credit.signum() >= 0 && (line.debit.signum() > 0) != (line.credit.signum() > 0)) {
                "Each line is either a debit or a credit, above zero"
            }
            requireAmount(line.debit.max(line.credit))
            LineEntry(line.accountId, line.debit, line.credit)
        }
    }

    private fun requireMoney(societyId: Long, accountId: Long): Long {
        val account = chartService.requirePostable(societyId, accountId)
        require(account.id in chartService.moneyAccountIds(societyId)) { "${account.name} is not a cash or bank account" }
        return account.id!!
    }

    private fun requireNotMember(systemKey: String?, name: String) =
        require(systemKey !in SystemAccounts.PER_FLAT) { "$name is kept by maintenance and can't be changed by hand" }

    private fun requireFlat(societyId: Long, flatId: Long) =
        require(flatRepository.findBySocietyId(societyId).any { it.id == flatId }) { "Flat $flatId is not in this society" }

    private fun requireAmount(amount: BigDecimal) {
        require(amount.signum() > 0) { "Amounts must be above zero" }
        require(amount.scale() <= 2) { "Amounts can have at most 2 decimal places" }
    }

    private fun requirePayee(payee: String): String = clean(payee, 200) ?: throw IllegalArgumentException("Say who was paid")

    private fun requireNarration(narration: String): String =
        clean(narration, 500) ?: throw IllegalArgumentException("A journal entry needs a narration explaining it")

    private fun approvalLimit(societyId: Long): BigDecimal =
        BigDecimal.valueOf(settings.integer(societyId, SocietySettingKeys.FINANCE_APPROVAL_ABOVE))

    private fun total(lines: List<AmountLine>): BigDecimal = lines.fold(BigDecimal.ZERO) { sum, line -> sum + line.amount }

    @JvmName("totalOfEntries")
    private fun total(lines: List<LineEntry>): BigDecimal = lines.fold(BigDecimal.ZERO) { sum, line -> sum + line.debit }

    private fun clean(text: String?, max: Int): String? = text?.trim()?.ifEmpty { null }?.also {
        require(it.length <= max) { "Text can be at most $max characters" }
    }

    private fun snapshot(voucher: Voucher): Map<String, Any?> = mapOf(
        "number" to voucher.number,
        "type" to voucher.type,
        "date" to voucher.voucherDate,
        "status" to voucher.status,
        "amount" to voucher.amount,
        "payee" to voucher.payee,
        "reference" to voucher.reference,
        "narration" to voucher.narration,
        "flatId" to voucher.flatId,
        "lines" to voucher.lines.map { mapOf("accountId" to it.accountId, "debit" to it.debit, "credit" to it.credit) },
    )

    private companion object {
        val MANUAL = setOf(VoucherType.RECEIPT, VoucherType.PAYMENT, VoucherType.CONTRA, VoucherType.JOURNAL)
        val APPROVABLE = setOf(VoucherType.PAYMENT, VoucherType.JOURNAL)
    }
}
