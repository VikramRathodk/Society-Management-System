package org.devvikram.societymanagement.finance.repository

import org.devvikram.societymanagement.finance.model.Voucher
import org.devvikram.societymanagement.finance.model.VoucherStatus
import org.devvikram.societymanagement.finance.model.VoucherType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate

// Debit and credit totals of one account (over some set of lines).
data class AccountTotals(val accountId: Long, val debit: BigDecimal, val credit: BigDecimal)

// One line of an account ledger, with what the reader needs from its voucher.
data class LedgerRow(
    val voucherId: Long,
    val number: String,
    val type: VoucherType,
    val date: LocalDate,
    val narration: String?,
    val payee: String?,
    val flatId: Long?,
    val debit: BigDecimal,
    val credit: BigDecimal,
)

interface VoucherRepository {
    fun save(voucher: Voucher): Voucher
    // With its lines.
    fun findById(id: Long): Voucher?
    fun maxSeq(societyId: Long, type: VoucherType, financialYear: String): Int?
    // The opening voucher of the books, with its lines.
    fun findOpening(societyId: Long): Voucher?
    fun countOtherThan(societyId: Long, type: VoucherType): Long

    // Totals per account of the vouchers in the books (POSTED, VOID) dated up to `upTo`.
    fun totalsByAccount(societyId: Long, upTo: LocalDate): List<AccountTotals>
    // Totals of one account (one flat's part, if given) dated before `before`.
    fun totalsBefore(accountId: Long, flatId: Long?, before: LocalDate): AccountTotals
    // Lines of one account (one flat's, if given) dated within [from, to], oldest first.
    fun ledger(accountId: Long, flatId: Long?, from: LocalDate, to: LocalDate): List<LedgerRow>
}

interface VoucherJpaRepository : JpaRepository<Voucher, Long> {
    @Query("select distinct v from Voucher v left join fetch v.lines where v.id = :id")
    fun findWithLinesById(@Param("id") id: Long): Voucher?

    @Query("select max(v.seq) from Voucher v where v.societyId = :s and v.type = :type and v.financialYear = :fy")
    fun maxSeq(@Param("s") societyId: Long, @Param("type") type: VoucherType, @Param("fy") financialYear: String): Int?

    @Query("select distinct v from Voucher v left join fetch v.lines where v.societyId = :s and v.type = :type")
    fun findWithLinesByType(@Param("s") societyId: Long, @Param("type") type: VoucherType): List<Voucher>

    @Query("select count(v) from Voucher v where v.societyId = :s and v.type <> :type")
    fun countOtherThan(@Param("s") societyId: Long, @Param("type") type: VoucherType): Long

    @Query(
        """
        select new org.devvikram.societymanagement.finance.repository.AccountTotals(l.accountId, sum(l.debit), sum(l.credit))
        from VoucherLine l join l.voucher v
        where v.societyId = :s and v.status in :statuses and v.voucherDate <= :upTo
        group by l.accountId
        """
    )
    fun totalsByAccount(
        @Param("s") societyId: Long,
        @Param("statuses") statuses: Collection<VoucherStatus>,
        @Param("upTo") upTo: LocalDate,
    ): List<AccountTotals>

    @Query(
        """
        select coalesce(sum(l.debit), 0), coalesce(sum(l.credit), 0)
        from VoucherLine l join l.voucher v
        where l.accountId = :a and v.status in :statuses and v.voucherDate < :before
        """
    )
    fun totalsBefore(
        @Param("a") accountId: Long,
        @Param("statuses") statuses: Collection<VoucherStatus>,
        @Param("before") before: LocalDate,
    ): List<Array<Any>>

    @Query(
        """
        select coalesce(sum(l.debit), 0), coalesce(sum(l.credit), 0)
        from VoucherLine l join l.voucher v
        where l.accountId = :a and l.flatId = :flat and v.status in :statuses and v.voucherDate < :before
        """
    )
    fun totalsBeforeForFlat(
        @Param("a") accountId: Long,
        @Param("flat") flatId: Long,
        @Param("statuses") statuses: Collection<VoucherStatus>,
        @Param("before") before: LocalDate,
    ): List<Array<Any>>

    @Query(
        """
        select new org.devvikram.societymanagement.finance.repository.LedgerRow(
            v.id, v.number, v.type, v.voucherDate, v.narration, v.payee, l.flatId, l.debit, l.credit)
        from VoucherLine l join l.voucher v
        where l.accountId = :a and v.status in :statuses and v.voucherDate between :from and :to
        order by v.voucherDate, v.id, l.lineOrder
        """
    )
    fun ledger(
        @Param("a") accountId: Long,
        @Param("statuses") statuses: Collection<VoucherStatus>,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<LedgerRow>

    @Query(
        """
        select new org.devvikram.societymanagement.finance.repository.LedgerRow(
            v.id, v.number, v.type, v.voucherDate, v.narration, v.payee, l.flatId, l.debit, l.credit)
        from VoucherLine l join l.voucher v
        where l.accountId = :a and l.flatId = :flat and v.status in :statuses and v.voucherDate between :from and :to
        order by v.voucherDate, v.id, l.lineOrder
        """
    )
    fun ledgerForFlat(
        @Param("a") accountId: Long,
        @Param("flat") flatId: Long,
        @Param("statuses") statuses: Collection<VoucherStatus>,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<LedgerRow>
}

@Repository
class VoucherRepositoryImpl(
    private val voucherJpaRepository: VoucherJpaRepository,
) : VoucherRepository {
    override fun save(voucher: Voucher): Voucher = voucherJpaRepository.save(voucher)

    override fun findById(id: Long): Voucher? = voucherJpaRepository.findWithLinesById(id)

    override fun maxSeq(societyId: Long, type: VoucherType, financialYear: String): Int? =
        voucherJpaRepository.maxSeq(societyId, type, financialYear)

    override fun findOpening(societyId: Long): Voucher? =
        voucherJpaRepository.findWithLinesByType(societyId, VoucherType.OPENING).firstOrNull()

    override fun countOtherThan(societyId: Long, type: VoucherType): Long =
        voucherJpaRepository.countOtherThan(societyId, type)

    override fun totalsByAccount(societyId: Long, upTo: LocalDate): List<AccountTotals> =
        voucherJpaRepository.totalsByAccount(societyId, VoucherStatus.IN_BOOKS, upTo)

    override fun totalsBefore(accountId: Long, flatId: Long?, before: LocalDate): AccountTotals {
        val row = if (flatId == null) {
            voucherJpaRepository.totalsBefore(accountId, VoucherStatus.IN_BOOKS, before)
        } else {
            voucherJpaRepository.totalsBeforeForFlat(accountId, flatId, VoucherStatus.IN_BOOKS, before)
        }.single()
        return AccountTotals(accountId, decimal(row[0]), decimal(row[1]))
    }

    override fun ledger(accountId: Long, flatId: Long?, from: LocalDate, to: LocalDate): List<LedgerRow> =
        if (flatId == null) {
            voucherJpaRepository.ledger(accountId, VoucherStatus.IN_BOOKS, from, to)
        } else {
            voucherJpaRepository.ledgerForFlat(accountId, flatId, VoucherStatus.IN_BOOKS, from, to)
        }

    private fun decimal(value: Any): BigDecimal = when (value) {
        is BigDecimal -> value
        is Number -> BigDecimal(value.toString())
        else -> error("Unexpected sum type ${value::class}")
    }
}
