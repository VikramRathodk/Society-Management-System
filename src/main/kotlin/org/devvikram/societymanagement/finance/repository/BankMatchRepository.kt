package org.devvikram.societymanagement.finance.repository

import jakarta.persistence.EntityManager
import org.devvikram.societymanagement.finance.model.BankLine
import org.devvikram.societymanagement.finance.model.BankLineState
import org.devvikram.societymanagement.finance.model.BankMatch
import org.devvikram.societymanagement.finance.model.VoucherStatus
import org.devvikram.societymanagement.finance.model.VoucherType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate

// A line of a bank ledger in the books, with what reconciling needs from its voucher. Debit is money
// into the bank, credit money out (the ledger's view, the opposite of the bank statement's).
data class BookEntryRow(
    val lineId: Long,
    val voucherId: Long,
    val number: String,
    val type: VoucherType,
    val date: LocalDate,
    val status: VoucherStatus,
    val reversesVoucherId: Long?,
    val narration: String?,
    val payee: String?,
    val reference: String?,
    val debit: BigDecimal,
    val credit: BigDecimal,
) {
    // Money in minus money out, comparable with BankLine.net.
    val net: BigDecimal get() = debit - credit
}

interface BankMatchRepository {
    fun saveAll(matches: List<BankMatch>): List<BankMatch>
    fun findByStatement(statementId: Long): List<BankMatch>
    fun findByBankLine(bankLineId: Long): List<BankMatch>
    fun findByFinanceLines(lineIds: Collection<Long>): List<BankMatch>
    fun deleteAll(matches: List<BankMatch>)

    // Lines of a bank ledger in the books (POSTED or VOID; never the opening balance, which is the bank's
    // own balance on the day the books started) dated from `from` (the account's first statement: earlier
    // entries count as cleared) up to `upTo`, not cleared by any statement ending on or before `upTo`.
    // For the latest statement that is simply "not cleared anywhere".
    fun uncleared(accountId: Long, from: LocalDate, upTo: LocalDate): List<BookEntryRow>
    // Balance of a bank ledger (debit − credit) at the start of `from`: the opening plus everything before.
    fun balanceAtStartOf(accountId: Long, from: LocalDate): BigDecimal
    // Balance at the end of `upTo`.
    fun balanceAtEndOf(accountId: Long, upTo: LocalDate): BigDecimal
    // The bank lines set aside (excluded) on this account's statements ending by `upTo`.
    fun excludedUpTo(accountId: Long, upTo: LocalDate): List<BankLine>
    // When each of these book lines cleared: its bank line's date, or the later of a pair's two dates.
    fun clearedOn(lineIds: Collection<Long>): Map<Long, LocalDate>
    fun entries(lineIds: Collection<Long>): List<BookEntryRow>
    // The bank ledger's lines of these vouchers.
    fun entriesOfVouchers(accountId: Long, voucherIds: Collection<Long>): List<BookEntryRow>
    fun findLine(id: Long): BankLine?
    fun saveLine(line: BankLine): BankLine
    // Bank lines waiting for a voucher recorded from them.
    fun findLinesByVoucher(voucherId: Long): List<BankLine>
}

interface BankMatchJpaRepository : JpaRepository<BankMatch, Long> {
    fun findByStatementIdOrderById(statementId: Long): List<BankMatch>
    fun findByBankLineIdOrderById(bankLineId: Long): List<BankMatch>
    fun findByFinanceLineIdIn(lineIds: Collection<Long>): List<BankMatch>
}

@Repository
class BankMatchRepositoryImpl(
    private val matches: BankMatchJpaRepository,
    private val lines: BankLineJpaRepository,
    private val entityManager: EntityManager,
) : BankMatchRepository {
    override fun saveAll(matches: List<BankMatch>): List<BankMatch> = this.matches.saveAllAndFlush(matches)

    override fun findByStatement(statementId: Long): List<BankMatch> = matches.findByStatementIdOrderById(statementId)

    override fun findByBankLine(bankLineId: Long): List<BankMatch> = matches.findByBankLineIdOrderById(bankLineId)

    override fun findByFinanceLines(lineIds: Collection<Long>): List<BankMatch> =
        if (lineIds.isEmpty()) emptyList() else lineIds.chunked(1000).flatMap(matches::findByFinanceLineIdIn)

    override fun deleteAll(matches: List<BankMatch>) {
        this.matches.deleteAll(matches)
        this.matches.flush()
    }

    override fun uncleared(accountId: Long, from: LocalDate, upTo: LocalDate): List<BookEntryRow> =
        entityManager.createQuery(
            "$SELECT where l.accountId = :a and v.status in :statuses and v.type <> :opening " +
                "and v.voucherDate between :from and :upTo and not exists (select 1 from BankMatch m, BankStatement s " +
                "where m.financeLineId = l.id and s.id = m.statementId and s.periodTo <= :upTo) " +
                "order by v.voucherDate, v.id, l.id",
            BookEntryRow::class.java,
        )
            .setParameter("a", accountId)
            .setParameter("statuses", VoucherStatus.IN_BOOKS)
            .setParameter("opening", VoucherType.OPENING)
            .setParameter("from", from)
            .setParameter("upTo", upTo)
            .resultList

    override fun balanceAtStartOf(accountId: Long, from: LocalDate): BigDecimal =
        balance("(v.type = :opening or v.voucherDate < :d)", accountId, from)

    override fun balanceAtEndOf(accountId: Long, upTo: LocalDate): BigDecimal =
        balance("(v.type = :opening or v.voucherDate <= :d)", accountId, upTo)

    override fun excludedUpTo(accountId: Long, upTo: LocalDate): List<BankLine> =
        entityManager.createQuery(
            "select b from BankLine b, BankStatement s where s.id = b.statementId and b.accountId = :a " +
                "and b.state = :excluded and s.periodTo <= :upTo order by b.txnDate, b.id",
            BankLine::class.java,
        )
            .setParameter("a", accountId)
            .setParameter("excluded", BankLineState.EXCLUDED)
            .setParameter("upTo", upTo)
            .resultList

    override fun clearedOn(lineIds: Collection<Long>): Map<Long, LocalDate> {
        val matches = findByFinanceLines(lineIds)
        if (matches.isEmpty()) return emptyMap()
        val bankDates = lines.findAllById(matches.mapNotNull { it.bankLineId }.toSet()).associate { it.id!! to it.txnDate }
        val pairDates = entries(matches.filter { it.pairLineId != null }.flatMap { listOf(it.financeLineId, it.pairLineId!!) }.toSet())
            .associate { it.lineId to it.date }
        return matches.associate { m ->
            m.financeLineId to (m.bankLineId?.let(bankDates::getValue)
                ?: maxOf(pairDates.getValue(m.financeLineId), pairDates.getValue(m.pairLineId!!)))
        }
    }

    private fun balance(dateRule: String, accountId: Long, date: LocalDate): BigDecimal {
        val row = entityManager.createQuery(
            "select coalesce(sum(l.debit), 0), coalesce(sum(l.credit), 0) from VoucherLine l join l.voucher v " +
                "where l.accountId = :a and v.status in :statuses and $dateRule",
            Array<Any>::class.java,
        )
            .setParameter("a", accountId)
            .setParameter("statuses", VoucherStatus.IN_BOOKS)
            .setParameter("opening", VoucherType.OPENING)
            .setParameter("d", date)
            .singleResult
        return (BigDecimal(row[0].toString()) - BigDecimal(row[1].toString())).setScale(2)
    }

    override fun entries(lineIds: Collection<Long>): List<BookEntryRow> =
        if (lineIds.isEmpty()) emptyList() else lineIds.chunked(1000).flatMap { ids ->
            entityManager.createQuery("$SELECT where l.id in :ids order by v.voucherDate, v.id, l.id", BookEntryRow::class.java)
                .setParameter("ids", ids)
                .resultList
        }

    override fun entriesOfVouchers(accountId: Long, voucherIds: Collection<Long>): List<BookEntryRow> =
        if (voucherIds.isEmpty()) emptyList() else
            entityManager.createQuery("$SELECT where l.accountId = :a and v.id in :ids order by v.voucherDate, v.id, l.id", BookEntryRow::class.java)
                .setParameter("a", accountId)
                .setParameter("ids", voucherIds)
                .resultList

    override fun findLine(id: Long): BankLine? = lines.findById(id).orElse(null)

    override fun saveLine(line: BankLine): BankLine = lines.saveAndFlush(line)

    override fun findLinesByVoucher(voucherId: Long): List<BankLine> = lines.findByVoucherId(voucherId)

    companion object {
        private const val SELECT =
            "select new org.devvikram.societymanagement.finance.repository.BookEntryRow(l.id, v.id, v.number, v.type, " +
                "v.voucherDate, v.status, v.reversesVoucherId, v.narration, v.payee, v.reference, l.debit, l.credit) " +
                "from VoucherLine l join l.voucher v "
    }
}
