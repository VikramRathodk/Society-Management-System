package org.devvikram.societymanagement.finance.repository

import jakarta.persistence.EntityManager
import org.devvikram.societymanagement.finance.model.BankLine
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
    // own balance on the day the books started) dated up to `upTo` that haven't cleared anywhere yet.
    fun uncleared(accountId: Long, upTo: LocalDate): List<BookEntryRow>
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
        if (lineIds.isEmpty()) emptyList() else matches.findByFinanceLineIdIn(lineIds)

    override fun deleteAll(matches: List<BankMatch>) {
        this.matches.deleteAll(matches)
        this.matches.flush()
    }

    override fun uncleared(accountId: Long, upTo: LocalDate): List<BookEntryRow> =
        entityManager.createQuery(
            "$SELECT where l.accountId = :a and v.status in :statuses and v.type <> :opening and v.voucherDate <= :upTo " +
                "and not exists (select 1 from BankMatch m where m.financeLineId = l.id) order by v.voucherDate, v.id, l.id",
            BookEntryRow::class.java,
        )
            .setParameter("a", accountId)
            .setParameter("statuses", VoucherStatus.IN_BOOKS)
            .setParameter("opening", VoucherType.OPENING)
            .setParameter("upTo", upTo)
            .resultList

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
