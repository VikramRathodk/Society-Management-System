package org.devvikram.societymanagement.finance.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

// `debitNormal`: the side a positive balance sits on. Assets and expenses grow with debits; liabilities,
// funds and income with credits. Balances shown to users are in this "natural" sign.
enum class AccountType(val debitNormal: Boolean) {
    ASSET(true),
    LIABILITY(false),
    FUND(false),
    INCOME(false),
    EXPENSE(true);

    // A line's effect on a balance of this type, in natural sign.
    fun natural(debit: BigDecimal, credit: BigDecimal): BigDecimal = if (debitNormal) debit - credit else credit - debit
}

// Accounts the books themselves rely on. They can be renamed (and the ledgers moved within their type),
// never deleted, retyped or deactivated.
object SystemAccounts {
    // Groups
    const val CASH_AND_BANK = "CASH_AND_BANK"
    const val BANK_ACCOUNTS = "BANK_ACCOUNTS"

    // Ledgers
    const val CASH = "CASH"
    const val MEMBER_DUES = "MEMBER_DUES"
    const val MEMBER_ADVANCE = "MEMBER_ADVANCE"
    const val MAINTENANCE_INCOME = "MAINTENANCE_INCOME"
    const val LATE_FEE_INCOME = "LATE_FEE_INCOME"
    const val WRITE_OFF = "WRITE_OFF"
    const val GENERAL_FUND = "GENERAL_FUND"

    // Per-flat sub-ledgers: every line on them names a flat (also enforced by the database).
    val PER_FLAT = setOf(MEMBER_DUES, MEMBER_ADVANCE)
}

// One account in a society's chart. A group only holds other accounts; a ledger is what lines post to.
// Ids are kept as plain columns (not associations): the chart is small and always handled as a whole.
@Entity
@Table(name = "finance_account")
class FinanceAccount(
    @Column(name = "society_id", nullable = false)
    var societyId: Long,

    @Column(nullable = false)
    var code: String,

    @Column(nullable = false)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: AccountType,

    // Null only for the root group of each type.
    @Column(name = "parent_id")
    var parentId: Long?,

    @Column(name = "is_group", nullable = false)
    var isGroup: Boolean,

    @Column(name = "system_key")
    var systemKey: String? = null,

    @Column(nullable = false)
    var active: Boolean = true,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "updated_at")
    var updatedAt: Instant? = null

    val isRoot: Boolean get() = parentId == null

    // Roots and system accounts keep their place in the books.
    val isProtected: Boolean get() = isRoot || systemKey != null

    @PrePersist
    fun onCreate() {
        if (createdAt == null) createdAt = Instant.now()
    }
}
