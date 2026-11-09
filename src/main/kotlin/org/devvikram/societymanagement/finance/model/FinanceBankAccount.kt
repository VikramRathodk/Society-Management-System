package org.devvikram.societymanagement.finance.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table

enum class BankAccountType { SAVINGS, CURRENT }

// Details of a bank ledger (same id as its FinanceAccount). Only the last 4 digits of the account number
// are kept, so nothing here is secret.
@Entity
@Table(name = "finance_bank_account")
class FinanceBankAccount(
    @Id
    @Column(name = "account_id")
    var accountId: Long,

    @Column(name = "bank_name", nullable = false)
    var bankName: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    var accountType: BankAccountType,

    @Column(name = "last4")
    var last4: String? = null,

    @Column(name = "ifsc")
    var ifsc: String? = null,
)
