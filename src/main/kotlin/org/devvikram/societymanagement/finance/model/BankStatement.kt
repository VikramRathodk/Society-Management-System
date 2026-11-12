package org.devvikram.societymanagement.finance.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class AmountStyle { DEBIT_CREDIT, AMOUNT_TYPE, SIGNED }

// How one bank's CSV export reads, saved per bank account. Columns are 1-based; the header row holds
// the column names and data starts on the next line.
@Entity
@Table(name = "finance_statement_mapping")
class StatementMapping(
    @Id
    @Column(name = "account_id")
    var accountId: Long,

    @Column(nullable = false)
    var delimiter: String,

    @Column(name = "header_row", nullable = false)
    var headerRow: Int,

    @Column(name = "date_column", nullable = false)
    var dateColumn: Int,

    @Column(name = "date_format", nullable = false)
    var dateFormat: String,

    @Column(name = "description_column", nullable = false)
    var descriptionColumn: Int,

    @Column(name = "reference_column")
    var referenceColumn: Int?,

    @Enumerated(EnumType.STRING)
    @Column(name = "amount_style", nullable = false)
    var amountStyle: AmountStyle,

    @Column(name = "debit_column")
    var debitColumn: Int?,

    @Column(name = "credit_column")
    var creditColumn: Int?,

    @Column(name = "amount_column")
    var amountColumn: Int?,

    @Column(name = "type_column")
    var typeColumn: Int?,

    @Column(name = "balance_column")
    var balanceColumn: Int?,

    @Column(name = "updated_by", nullable = false)
    var updatedBy: Long,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

enum class StatementStatus { OPEN, COMPLETED }

// One bank statement period of a bank ledger. Balances are as the bank shows them: positive is money in
// the account, negative is overdrawn.
@Entity
@Table(name = "finance_bank_statement")
class BankStatement(
    @Column(name = "society_id", nullable = false)
    var societyId: Long,

    @Column(name = "account_id", nullable = false)
    var accountId: Long,

    @Column(name = "period_from", nullable = false)
    var periodFrom: LocalDate,

    @Column(name = "period_to", nullable = false)
    var periodTo: LocalDate,

    @Column(name = "opening_balance", nullable = false)
    var openingBalance: BigDecimal,

    @Column(name = "closing_balance", nullable = false)
    var closingBalance: BigDecimal,

    @Column(name = "created_by", nullable = false)
    var createdBy: Long,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: StatementStatus = StatementStatus.OPEN

    @Column(name = "updated_at")
    var updatedAt: Instant? = null

    @Column(name = "completed_at")
    var completedAt: Instant? = null

    @Column(name = "completed_by")
    var completedBy: Long? = null

    @Column(name = "reopened_at")
    var reopenedAt: Instant? = null

    @Column(name = "reopened_by")
    var reopenedBy: Long? = null

    @Column(name = "reopen_reason")
    var reopenReason: String? = null

    @Column(name = "book_balance")
    var bookBalance: BigDecimal? = null

    @Column(name = "uncleared_receipts")
    var unclearedReceipts: BigDecimal? = null

    @Column(name = "uncleared_payments")
    var unclearedPayments: BigDecimal? = null

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}

// One CSV uploaded into a statement; the file is kept in the shared storage.
@Entity
@Table(name = "finance_bank_statement_import")
class StatementImport(
    @Column(name = "statement_id", nullable = false)
    var statementId: Long,

    @Column
    var filename: String?,

    @Column(name = "storage_key", nullable = false)
    var storageKey: String,

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Int,

    @Column(name = "rows_imported", nullable = false)
    var rowsImported: Int,

    @Column(name = "rows_duplicate", nullable = false)
    var rowsDuplicate: Int,

    @Column(name = "rows_ignored", nullable = false)
    var rowsIgnored: Int,

    @Column(name = "imported_by", nullable = false)
    var importedBy: Long,

    @Column(name = "imported_at", nullable = false)
    var importedAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

enum class BankLineState { UNMATCHED, MATCHED, EXCLUDED }

// One transaction of a bank statement: money out is a debit, money in a credit (the bank's view).
@Entity
@Table(name = "finance_bank_line")
class BankLine(
    @Column(name = "statement_id", nullable = false)
    var statementId: Long,

    @Column(name = "import_id", nullable = false)
    var importId: Long,

    @Column(name = "account_id", nullable = false)
    var accountId: Long,

    @Column(name = "line_no", nullable = false)
    var lineNo: Int,

    @Column(name = "txn_date", nullable = false)
    var txnDate: LocalDate,

    @Column(nullable = false)
    var description: String,

    @Column
    var reference: String?,

    @Column(nullable = false)
    var debit: BigDecimal,

    @Column(nullable = false)
    var credit: BigDecimal,

    @Column
    var balance: BigDecimal?,

    @Column(nullable = false)
    var fingerprint: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: BankLineState = BankLineState.UNMATCHED

    @Column(name = "exclude_reason")
    var excludeReason: String? = null

    @Column(name = "voucher_id")
    var voucherId: Long? = null

    // Money in minus money out.
    val net: BigDecimal get() = credit - debit
}
