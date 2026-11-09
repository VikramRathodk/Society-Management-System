package org.devvikram.societymanagement.finance.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

// A society's books, once started. Until this row exists nothing is posted.
@Entity
@Table(name = "finance_books")
class FinanceBooks(
    @Id
    @Column(name = "society_id")
    var societyId: Long,

    @Column(name = "start_date", nullable = false)
    var startDate: LocalDate,

    @Column(name = "started_by", nullable = false)
    var startedBy: Long,

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.now(),
) {
    // No voucher may be dated, approved or voided on or before this day.
    @Column(name = "locked_until")
    var lockedUntil: LocalDate? = null

    // Where maintenance payments are deposited, per method; and the usual account expenses are paid from.
    @Column(name = "cash_deposit_account_id")
    var cashDepositAccountId: Long? = null

    @Column(name = "cheque_deposit_account_id")
    var chequeDepositAccountId: Long? = null

    @Column(name = "upi_deposit_account_id")
    var upiDepositAccountId: Long? = null

    @Column(name = "transfer_deposit_account_id")
    var transferDepositAccountId: Long? = null

    @Column(name = "default_paying_account_id")
    var defaultPayingAccountId: Long? = null

    fun referencedAccountIds(): Set<Long> = setOfNotNull(
        cashDepositAccountId, chequeDepositAccountId, upiDepositAccountId, transferDepositAccountId,
        defaultPayingAccountId,
    )
}
