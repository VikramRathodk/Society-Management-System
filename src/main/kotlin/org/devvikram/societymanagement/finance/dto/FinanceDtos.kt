package org.devvikram.societymanagement.finance.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.devvikram.societymanagement.finance.model.AccountType
import org.devvikram.societymanagement.finance.model.AuditAction
import org.devvikram.societymanagement.finance.model.AuditEntityType
import org.devvikram.societymanagement.finance.model.BankAccountType
import org.devvikram.societymanagement.finance.model.FinanceAccount
import org.devvikram.societymanagement.finance.model.FinanceAuditEntry
import org.devvikram.societymanagement.finance.model.FinanceBankAccount
import org.devvikram.societymanagement.finance.model.VoucherType
import org.devvikram.societymanagement.finance.service.AccountView
import org.devvikram.societymanagement.finance.service.AddBankAccountCommand
import org.devvikram.societymanagement.finance.service.BooksState
import org.devvikram.societymanagement.finance.service.CorrectOpeningsCommand
import org.devvikram.societymanagement.finance.service.CreateAccountCommand
import org.devvikram.societymanagement.finance.service.LedgerView
import org.devvikram.societymanagement.finance.service.OpeningEntry
import org.devvikram.societymanagement.finance.service.OpeningsView
import org.devvikram.societymanagement.finance.service.StartBooksCommand
import org.devvikram.societymanagement.finance.service.UpdateAccountCommand
import org.devvikram.societymanagement.finance.service.UpdateBankAccountCommand
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// ---- Books ----

data class BooksResponse(
    val societyId: Long,
    val started: Boolean,
    val hasChart: Boolean,
    val startDate: LocalDate?,
    val lockedUntil: LocalDate?,
    val openingVoucherId: Long?,
    val openingVoucherNumber: String?,
    val openingsEditable: Boolean,
    val defaults: BookDefaultsResponse?,
) {
    companion object {
        fun from(state: BooksState) = BooksResponse(
            societyId = state.societyId,
            started = state.books != null,
            hasChart = state.hasChart,
            startDate = state.books?.startDate,
            lockedUntil = state.books?.lockedUntil,
            openingVoucherId = state.opening?.id,
            openingVoucherNumber = state.opening?.number,
            openingsEditable = state.openingsEditable,
            defaults = state.books?.let {
                BookDefaultsResponse(
                    cashDepositAccountId = it.cashDepositAccountId,
                    chequeDepositAccountId = it.chequeDepositAccountId,
                    upiDepositAccountId = it.upiDepositAccountId,
                    transferDepositAccountId = it.transferDepositAccountId,
                    defaultPayingAccountId = it.defaultPayingAccountId,
                )
            },
        )
    }
}

data class BookDefaultsResponse(
    val cashDepositAccountId: Long?,
    val chequeDepositAccountId: Long?,
    val upiDepositAccountId: Long?,
    val transferDepositAccountId: Long?,
    val defaultPayingAccountId: Long?,
)

data class ChartSeededResponse(val accounts: Int)

// `amount` is in the account's natural sign: positive on its usual side, negative on the other.
data class OpeningEntryRequest(
    @field:NotNull val accountId: Long?,
    @field:NotNull @field:Digits(integer = 12, fraction = 2) val amount: BigDecimal?,
) {
    fun toEntry() = OpeningEntry(accountId!!, amount!!)
}

data class StartBooksRequest(
    @field:NotNull val startDate: LocalDate?,
    @field:Valid val openings: List<OpeningEntryRequest> = emptyList(),
) {
    fun toCommand() = StartBooksCommand(startDate!!, openings.map { it.toEntry() })
}

data class CorrectOpeningsRequest(
    val startDate: LocalDate? = null,
    @field:Valid val openings: List<OpeningEntryRequest> = emptyList(),
    @field:NotBlank @field:Size(max = 500) val reason: String?,
) {
    fun toCommand() = CorrectOpeningsCommand(startDate, openings.map { it.toEntry() }, reason!!)
}

data class OpeningLineResponse(val accountId: Long, val code: String, val name: String, val type: AccountType, val amount: BigDecimal)

data class OpeningsResponse(
    val startDate: LocalDate,
    val voucherId: Long,
    val voucherNumber: String,
    val entries: List<OpeningLineResponse>,
    // What balances the openings, credit positive.
    val generalFund: BigDecimal,
    // Totals of the per-flat openings taken from maintenance.
    val memberDues: BigDecimal,
    val memberAdvances: BigDecimal,
    val editable: Boolean,
) {
    companion object {
        fun from(view: OpeningsView, accounts: Map<Long, FinanceAccount>, editable: Boolean) = OpeningsResponse(
            startDate = view.startDate,
            voucherId = view.voucher.id!!,
            voucherNumber = view.voucher.number,
            entries = view.entries.map {
                val account = accounts.getValue(it.accountId)
                OpeningLineResponse(account.id!!, account.code, account.name, account.type, it.amount)
            },
            generalFund = view.generalFund,
            memberDues = view.memberDues,
            memberAdvances = view.memberAdvances,
            editable = editable,
        )
    }
}

// ---- Accounts ----

data class BankDetailsResponse(val bankName: String, val accountType: BankAccountType, val last4: String?, val ifsc: String?) {
    companion object {
        fun from(details: FinanceBankAccount) = BankDetailsResponse(details.bankName, details.accountType, details.last4, details.ifsc)
    }
}

data class AccountResponse(
    val id: Long,
    val code: String,
    val name: String,
    val type: AccountType,
    val parentId: Long?,
    val group: Boolean,
    val systemKey: String?,
    val active: Boolean,
    // Natural sign; for a group, the total of everything under it.
    val balance: BigDecimal,
    val bank: BankDetailsResponse?,
) {
    companion object {
        fun from(view: AccountView) = AccountResponse(
            id = view.account.id!!,
            code = view.account.code,
            name = view.account.name,
            type = view.account.type,
            parentId = view.account.parentId,
            group = view.account.isGroup,
            systemKey = view.account.systemKey,
            active = view.account.active,
            balance = view.balance,
            bank = view.bank?.let(BankDetailsResponse::from),
        )
    }
}

data class CreateAccountRequest(
    @field:NotBlank @field:Size(max = 20) val code: String?,
    @field:NotBlank @field:Size(max = 100) val name: String?,
    @field:NotNull val parentId: Long?,
    val group: Boolean = false,
) {
    fun toCommand() = CreateAccountCommand(code!!, name!!, parentId!!, group)
}

data class UpdateAccountRequest(
    @field:Size(max = 20) val code: String? = null,
    @field:Size(max = 100) val name: String? = null,
    val parentId: Long? = null,
    val group: Boolean? = null,
    val active: Boolean? = null,
) {
    fun toCommand() = UpdateAccountCommand(code, name, parentId, group, active)
}

data class AddBankAccountRequest(
    @field:NotBlank @field:Size(max = 100) val name: String?,
    @field:NotBlank @field:Size(max = 100) val bankName: String?,
    @field:NotNull val accountType: BankAccountType?,
    @field:Size(max = 4) val last4: String? = null,
    @field:Size(max = 11) val ifsc: String? = null,
    @field:Size(max = 20) val code: String? = null,
) {
    fun toCommand() = AddBankAccountCommand(name!!, bankName!!, accountType!!, last4, ifsc, code)
}

data class UpdateBankAccountRequest(
    @field:Size(max = 100) val name: String? = null,
    @field:Size(max = 100) val bankName: String? = null,
    val accountType: BankAccountType? = null,
    @field:Size(max = 4) val last4: String? = null,
    @field:Size(max = 11) val ifsc: String? = null,
    val active: Boolean? = null,
) {
    fun toCommand() = UpdateBankAccountCommand(name, bankName, accountType, last4, ifsc, active)
}

// ---- Ledger ----

data class LedgerLineResponse(
    val voucherId: Long,
    val voucherNumber: String,
    val voucherType: VoucherType,
    val date: LocalDate,
    val narration: String?,
    val payee: String?,
    val flatId: Long?,
    val flatLabel: String?,
    val debit: BigDecimal,
    val credit: BigDecimal,
    val balance: BigDecimal,
    // Bank ledgers only: the day this entry cleared at the bank (null while it hasn't yet).
    val clearedOn: LocalDate? = null,
)

data class LedgerResponse(
    val accountId: Long,
    val code: String,
    val name: String,
    val type: AccountType,
    val flatId: Long?,
    val flatLabel: String?,
    val from: LocalDate,
    val to: LocalDate,
    val opening: BigDecimal,
    val lines: List<LedgerLineResponse>,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
    val closing: BigDecimal,
) {
    companion object {
        fun from(view: LedgerView) = LedgerResponse(
            accountId = view.account.id!!,
            code = view.account.code,
            name = view.account.name,
            type = view.account.type,
            flatId = view.flatId,
            flatLabel = view.flatLabel,
            from = view.from,
            to = view.to,
            opening = view.opening,
            lines = view.lines.map {
                LedgerLineResponse(
                    voucherId = it.row.voucherId,
                    voucherNumber = it.row.number,
                    voucherType = it.row.type,
                    date = it.row.date,
                    narration = it.row.narration,
                    payee = it.row.payee,
                    flatId = it.row.flatId,
                    flatLabel = it.flatLabel,
                    debit = it.row.debit,
                    credit = it.row.credit,
                    balance = it.balance,
                    clearedOn = it.clearedOn,
                )
            },
            totalDebit = view.totalDebit,
            totalCredit = view.totalCredit,
            closing = view.closing,
        )
    }
}

// ---- Audit ----

data class AuditEntryResponse(
    val id: Long,
    val entityType: AuditEntityType,
    val entityId: Long?,
    val action: AuditAction,
    // The JSON objects as recorded.
    val oldValues: String?,
    val newValues: String?,
    val reason: String?,
    val performedBy: Long,
    val performedByName: String?,
    val performedAt: Instant,
) {
    companion object {
        fun from(entry: FinanceAuditEntry, names: Map<Long, String>) = AuditEntryResponse(
            id = entry.id!!,
            entityType = entry.entityType,
            entityId = entry.entityId,
            action = entry.action,
            oldValues = entry.oldValues,
            newValues = entry.newValues,
            reason = entry.reason,
            performedBy = entry.performedBy,
            performedByName = names[entry.performedBy],
            performedAt = entry.performedAt,
        )
    }
}
