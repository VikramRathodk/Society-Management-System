package org.devvikram.societymanagement.finance.model

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// `prefix` starts the voucher number: RV/2026-27/0001.
enum class VoucherType(val prefix: String) {
    RECEIPT("RV"),
    PAYMENT("PV"),
    CONTRA("CV"),
    JOURNAL("JV"),
    OPENING("OB"),
    SYSTEM("SV"),
}

// Only POSTED and VOID vouchers are in the books: a VOID one stays, and its linked reversal cancels it,
// so reports for earlier periods never change.
enum class VoucherStatus {
    PENDING_APPROVAL,
    POSTED,
    REJECTED,
    VOID;

    companion object {
        val IN_BOOKS = listOf(POSTED, VOID)
    }
}

// One balanced entry in the books: its lines' debits equal their credits (also checked by the database at
// commit).
@Entity
@Table(name = "finance_voucher")
class Voucher(
    @Column(name = "society_id", nullable = false)
    var societyId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: VoucherType,

    @Column(name = "financial_year", nullable = false)
    var financialYear: String,

    @Column(nullable = false)
    var seq: Int,

    @Column(nullable = false)
    var number: String,

    @Column(name = "voucher_date", nullable = false)
    var voucherDate: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: VoucherStatus,

    @Column(name = "created_by", nullable = false)
    var createdBy: Long,

    @Column
    var narration: String? = null,

    @Column
    var payee: String? = null,

    @Column
    var reference: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    // Total of the debit lines; kept in step with the lines by replaceLines().
    @Column(nullable = false, precision = 14, scale = 2)
    var amount: BigDecimal = BigDecimal.ZERO

    @Column(name = "source_type")
    var sourceType: String? = null

    @Column(name = "source_id")
    var sourceId: Long? = null

    @Column(name = "source_event")
    var sourceEvent: String? = null

    @Column(name = "reverses_voucher_id")
    var reversesVoucherId: Long? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "updated_at")
    var updatedAt: Instant? = null

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    @Column(name = "decided_by")
    var decidedBy: Long? = null

    @Column(name = "voided_at")
    var voidedAt: Instant? = null

    @Column(name = "voided_by")
    var voidedBy: Long? = null

    @Column
    var reason: String? = null

    @OneToMany(mappedBy = "voucher", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("lineOrder")
    var lines: MutableList<VoucherLine> = mutableListOf()

    // Replaces all lines (in the given order) and the voucher's amount.
    fun replaceLines(entries: List<LineEntry>) {
        lines.clear()
        entries.forEachIndexed { index, entry ->
            lines += VoucherLine(this, entry.accountId, entry.flatId, entry.debit, entry.credit, index)
        }
        amount = entries.fold(BigDecimal.ZERO) { sum, entry -> sum + entry.debit }
    }

    @PrePersist
    fun onCreate() {
        if (createdAt == null) createdAt = Instant.now()
    }
}

@Entity
@Table(name = "finance_line")
class VoucherLine(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voucher_id", nullable = false)
    var voucher: Voucher,

    @Column(name = "account_id", nullable = false)
    var accountId: Long,

    @Column(name = "flat_id")
    var flatId: Long?,

    @Column(nullable = false, precision = 14, scale = 2)
    var debit: BigDecimal,

    @Column(nullable = false, precision = 14, scale = 2)
    var credit: BigDecimal,

    @Column(name = "line_order", nullable = false)
    var lineOrder: Int,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

// A line before it's written: exactly one of debit and credit is above zero.
data class LineEntry(
    val accountId: Long,
    val debit: BigDecimal,
    val credit: BigDecimal,
    val flatId: Long? = null,
) {
    init {
        require(debit.signum() >= 0 && credit.signum() >= 0 && (debit.signum() > 0) != (credit.signum() > 0)) {
            "A line is either a debit or a credit, above zero"
        }
    }

    companion object {
        fun debit(accountId: Long, amount: BigDecimal, flatId: Long? = null) =
            LineEntry(accountId, amount, BigDecimal.ZERO, flatId)

        fun credit(accountId: Long, amount: BigDecimal, flatId: Long? = null) =
            LineEntry(accountId, BigDecimal.ZERO, amount, flatId)
    }
}
