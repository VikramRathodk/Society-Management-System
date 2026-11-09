package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.finance.model.AccountType
import org.devvikram.societymanagement.finance.model.AuditAction
import org.devvikram.societymanagement.finance.model.AuditEntityType
import org.devvikram.societymanagement.finance.model.BankAccountType
import org.devvikram.societymanagement.finance.model.FinanceAccount
import org.devvikram.societymanagement.finance.model.FinanceBankAccount
import org.devvikram.societymanagement.finance.model.SystemAccounts
import org.devvikram.societymanagement.finance.repository.AccountRepository
import org.devvikram.societymanagement.finance.repository.BooksRepository
import org.devvikram.societymanagement.finance.repository.VoucherRepository
import org.devvikram.societymanagement.society.service.SocietyService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class CreateAccountCommand(val code: String, val name: String, val parentId: Long, val isGroup: Boolean)

// null leaves a field unchanged.
data class UpdateAccountCommand(
    val code: String? = null,
    val name: String? = null,
    val parentId: Long? = null,
    val isGroup: Boolean? = null,
    val active: Boolean? = null,
)

// `code` is optional: a numeric code after the "Bank accounts" group's is picked.
data class AddBankAccountCommand(
    val name: String,
    val bankName: String,
    val accountType: BankAccountType,
    val last4: String? = null,
    val ifsc: String? = null,
    val code: String? = null,
)

// null keeps a field; for last4 and ifsc an empty string removes the saved value.
data class UpdateBankAccountCommand(
    val name: String? = null,
    val bankName: String? = null,
    val accountType: BankAccountType? = null,
    val last4: String? = null,
    val ifsc: String? = null,
    val active: Boolean? = null,
)

// An account with its balance (natural sign; a group's is the sum of everything under it) and, for a
// bank ledger, its bank details.
data class AccountView(val account: FinanceAccount, val balance: BigDecimal, val bank: FinanceBankAccount?)

// The chart of accounts. Rules: a child has its parent's type and the parent is a group; no cycles; only
// ledgers are posted to; roots and system accounts stay (renaming allowed, and system ledgers may move
// within their type); an account that was ever posted to is never deleted, only deactivated.
@Service
class ChartService(
    private val accountRepository: AccountRepository,
    private val voucherRepository: VoucherRepository,
    private val booksRepository: BooksRepository,
    private val societyService: SocietyService,
    private val audit: FinanceAudit,
) {
    fun get(id: Long): FinanceAccount = accountRepository.findById(id) ?: throw NoSuchElementException("Account $id not found")

    fun hasChart(societyId: Long): Boolean = accountRepository.existsBySocietyId(societyId)

    fun accounts(societyId: Long): List<FinanceAccount> {
        societyService.getById(societyId)
        return accountRepository.findBySocietyId(societyId)
    }

    // The whole chart by code, with balances of the books up to `asOf`.
    fun chart(societyId: Long, asOf: LocalDate): List<AccountView> {
        val accounts = accounts(societyId)
        val totals = voucherRepository.totalsByAccount(societyId, asOf).associateBy { it.accountId }
        val own = accounts.associate { account ->
            val t = totals[account.id]
            account.id!! to (t?.let { account.type.natural(it.debit, it.credit) } ?: BigDecimal.ZERO)
        }
        val children = accounts.groupBy { it.parentId }
        fun total(account: FinanceAccount): BigDecimal =
            children[account.id].orEmpty().fold(own.getValue(account.id!!)) { sum, child -> sum + total(child) }
        val banks = accountRepository.findBanks(accounts.filterNot { it.isGroup }.map { it.id!! }).associateBy { it.accountId }
        return accounts.map { AccountView(it, total(it).setScale(2), banks[it.id]) }
    }

    fun systemAccount(societyId: Long, systemKey: String): FinanceAccount =
        accountRepository.findBySystemKey(societyId, systemKey)
            ?: throw IllegalStateException("The books have no $systemKey account")

    // An account lines can be written to: a ledger of this society that is active.
    fun requirePostable(societyId: Long, accountId: Long): FinanceAccount {
        val account = accountRepository.findById(accountId)
        if (account == null || account.societyId != societyId) throw IllegalArgumentException("Account $accountId is not in this society's books")
        require(!account.isGroup) { "${account.name} is a group; choose one of the accounts under it" }
        require(account.active) { "${account.name} is inactive" }
        return account
    }

    // Seeds the default chart. Refused once the society has any account.
    @Transactional
    fun seedDefault(societyId: Long, userId: Long): Int {
        societyService.getById(societyId)
        booksRepository.lockSociety(societyId)
        if (accountRepository.existsBySocietyId(societyId)) throw ConflictException("This society already has a chart of accounts")
        var count = 0
        fun add(node: DefaultChart.Node, type: AccountType, parentId: Long?) {
            val saved = accountRepository.save(
                FinanceAccount(societyId, node.code, node.name, type, parentId, node.group, node.systemKey)
            )
            count++
            node.children.forEach { add(it, type, saved.id) }
        }
        DefaultChart.roots.forEach { (type, root) -> add(root, type, null) }
        audit.record(societyId, AuditEntityType.ACCOUNT, null, AuditAction.CREATE, userId,
            newValues = mapOf("defaultChart" to true, "accounts" to count))
        return count
    }

    // Seeds the default chart when the society has none yet; used when the books are started.
    @Transactional
    fun ensureChart(societyId: Long, userId: Long) {
        if (!accountRepository.existsBySocietyId(societyId)) seedDefault(societyId, userId)
    }

    @Transactional
    fun create(societyId: Long, command: CreateAccountCommand, userId: Long): Long {
        societyService.getById(societyId)
        booksRepository.lockSociety(societyId)
        val chart = accountRepository.findBySocietyId(societyId)
        val parent = parentFor(chart, societyId, command.parentId)
        val code = cleanCode(command.code)
        val name = cleanName(command.name)
        requireUnique(chart, code, name, exceptId = null)
        val account = accountRepository.save(FinanceAccount(societyId, code, name, parent.type, parent.id, command.isGroup))
        audit.record(societyId, AuditEntityType.ACCOUNT, account.id, AuditAction.CREATE, userId, newValues = snapshot(account))
        return account.id!!
    }

    @Transactional
    fun update(id: Long, command: UpdateAccountCommand, userId: Long) {
        val account = get(id)
        booksRepository.lockSociety(account.societyId)
        val chart = accountRepository.findBySocietyId(account.societyId)
        val before = snapshot(account)

        val code = command.code?.let(::cleanCode) ?: account.code
        val name = command.name?.let(::cleanName) ?: account.name
        requireUnique(chart, code, name, exceptId = account.id)
        account.code = code
        account.name = name

        if (command.parentId != null && command.parentId != account.parentId) {
            require(!account.isRoot) { "A root group can't be moved" }
            val parent = parentFor(chart, account.societyId, command.parentId)
            require(parent.type == account.type) { "${account.name} can only move within ${account.type.label()}" }
            require(account.id !in ancestorsOf(parent, chart).map { it.id } && parent.id != account.id) {
                "An account can't be moved under itself"
            }
            account.parentId = parent.id
        }

        if (command.isGroup != null && command.isGroup != account.isGroup) {
            require(!account.isProtected) { "${account.name} is a system account; it can't change between group and ledger" }
            if (command.isGroup) {
                if (accountRepository.hasLines(id)) throw ConflictException("${account.name} has entries, so it can't become a group")
            } else {
                if (chart.any { it.parentId == id }) throw ConflictException("${account.name} still holds accounts, so it can't become a ledger")
            }
            account.isGroup = command.isGroup
        }

        if (command.active != null && command.active != account.active) {
            require(!account.isProtected) { "${account.name} is a system account and stays active" }
            if (command.active) {
                val parent = chart.first { it.id == account.parentId }
                require(parent.active) { "Activate ${parent.name} first" }
            } else {
                if (descendantsOf(account, chart).any { it.active }) {
                    throw ConflictException("Deactivate the accounts under ${account.name} first")
                }
                if (id in booksRepository.findBySocietyId(account.societyId)?.referencedAccountIds().orEmpty()) {
                    throw ConflictException("${account.name} is a default account of the books; choose another default first")
                }
            }
            account.active = command.active
        }

        val after = snapshot(account)
        if (after == before) return
        account.updatedAt = Instant.now()
        accountRepository.save(account)
        audit.record(account.societyId, AuditEntityType.ACCOUNT, id, AuditAction.UPDATE, userId,
            oldValues = before.filter { (k, v) -> after[k] != v }, newValues = after.filter { (k, v) -> before[k] != v })
    }

    @Transactional
    fun delete(id: Long, userId: Long) {
        val account = get(id)
        booksRepository.lockSociety(account.societyId)
        require(!account.isProtected) { "${account.name} is a system account and can't be deleted" }
        val chart = accountRepository.findBySocietyId(account.societyId)
        if (chart.any { it.parentId == id }) throw ConflictException("${account.name} still holds accounts; move or delete them first")
        if (accountRepository.hasLines(id)) throw ConflictException("${account.name} has entries; deactivate it instead")
        if (id in booksRepository.findBySocietyId(account.societyId)?.referencedAccountIds().orEmpty()) {
            throw ConflictException("${account.name} is a default account of the books; choose another default first")
        }
        if (accountRepository.isMapped(id)) throw ConflictException("${account.name} is mapped from a maintenance charge head")
        val before = snapshot(account) + bankSnapshot(accountRepository.findBank(id))
        accountRepository.delete(account)
        audit.record(account.societyId, AuditEntityType.ACCOUNT, id, AuditAction.DELETE, userId, oldValues = before)
    }

    // ---- Bank accounts: ledgers under "Bank accounts" with their bank details ----

    @Transactional
    fun addBank(societyId: Long, command: AddBankAccountCommand, userId: Long): Long {
        societyService.getById(societyId)
        ensureChart(societyId, userId)
        booksRepository.lockSociety(societyId)
        val chart = accountRepository.findBySocietyId(societyId)
        val group = chart.firstOrNull { it.systemKey == SystemAccounts.BANK_ACCOUNTS }
            ?: throw IllegalStateException("The books have no Bank accounts group")
        val name = cleanName(command.name)
        val code = command.code?.let(::cleanCode) ?: nextCode(group, chart)
        requireUnique(chart, code, name, exceptId = null)
        val account = accountRepository.save(FinanceAccount(societyId, code, name, group.type, group.id, isGroup = false))
        val details = accountRepository.saveBank(
            FinanceBankAccount(
                accountId = account.id!!,
                bankName = cleanBankName(command.bankName),
                accountType = command.accountType,
                last4 = command.last4?.trim()?.ifEmpty { null }?.also(::requireLast4),
                ifsc = command.ifsc?.trim()?.uppercase()?.ifEmpty { null }?.also(::requireIfsc),
            )
        )
        audit.record(societyId, AuditEntityType.BANK_ACCOUNT, account.id, AuditAction.CREATE, userId,
            newValues = snapshot(account) + bankSnapshot(details))
        return account.id!!
    }

    @Transactional
    fun updateBank(id: Long, command: UpdateBankAccountCommand, userId: Long) {
        val details = accountRepository.findBank(id) ?: throw NoSuchElementException("Bank account $id not found")
        val before = bankSnapshot(details)
        update(id, UpdateAccountCommand(name = command.name, active = command.active), userId)
        command.bankName?.let { details.bankName = cleanBankName(it) }
        command.accountType?.let { details.accountType = it }
        command.last4?.let { details.last4 = it.trim().ifEmpty { null }?.also(::requireLast4) }
        command.ifsc?.let { details.ifsc = it.trim().uppercase().ifEmpty { null }?.also(::requireIfsc) }
        val after = bankSnapshot(details)
        if (after == before) return
        accountRepository.saveBank(details)
        audit.record(get(id).societyId, AuditEntityType.BANK_ACCOUNT, id, AuditAction.UPDATE, userId,
            oldValues = before.filter { (k, v) -> after[k] != v }, newValues = after.filter { (k, v) -> before[k] != v })
    }

    // ---- helpers ----

    private fun parentFor(chart: List<FinanceAccount>, societyId: Long, parentId: Long): FinanceAccount {
        val parent = chart.firstOrNull { it.id == parentId }
            ?: throw IllegalArgumentException("Parent account $parentId is not in this society's books")
        require(parent.isGroup) { "${parent.name} is a ledger; accounts can only go under a group" }
        require(parent.active) { "${parent.name} is inactive" }
        check(parent.societyId == societyId)
        return parent
    }

    private fun ancestorsOf(account: FinanceAccount, chart: List<FinanceAccount>): List<FinanceAccount> {
        val byId = chart.associateBy { it.id }
        return generateSequence(byId[account.parentId]) { byId[it.parentId] }.toList()
    }

    private fun descendantsOf(account: FinanceAccount, chart: List<FinanceAccount>): List<FinanceAccount> {
        val children = chart.groupBy { it.parentId }
        fun walk(a: FinanceAccount): List<FinanceAccount> = children[a.id].orEmpty().flatMap { listOf(it) + walk(it) }
        return walk(account)
    }

    private fun nextCode(group: FinanceAccount, chart: List<FinanceAccount>): String {
        val base = group.code.toIntOrNull()
            ?: throw IllegalArgumentException("Give the bank account a code: ${group.name}'s code isn't a number")
        val taken = chart.map { it.code }.toSet()
        return generateSequence(base + 1) { it + 1 }.map { it.toString() }.first { it !in taken }
    }

    private fun requireUnique(chart: List<FinanceAccount>, code: String, name: String, exceptId: Long?) {
        val others = chart.filter { it.id != exceptId }
        require(others.none { it.code == code }) { "Code $code is already used" }
        require(others.none { it.name.equals(name, ignoreCase = true) }) { "An account named $name already exists" }
    }

    private fun cleanCode(code: String): String {
        val trimmed = code.trim()
        require(CODE.matches(trimmed)) { "Code must be 1–20 letters, digits, '.', '-' or '/'" }
        return trimmed
    }

    private fun cleanName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 100) { "Name must be 1–100 characters" }
        return trimmed
    }

    private fun cleanBankName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 100) { "Bank name must be 1–100 characters" }
        return trimmed
    }

    private fun requireLast4(value: String) = require(LAST4.matches(value)) { "Give only the last 4 digits of the account number" }

    private fun requireIfsc(value: String) = require(IFSC.matches(value)) { "IFSC must look like HDFC0001234" }

    private fun snapshot(account: FinanceAccount): Map<String, Any?> = mapOf(
        "code" to account.code,
        "name" to account.name,
        "type" to account.type,
        "parentId" to account.parentId,
        "group" to account.isGroup,
        "active" to account.active,
    )

    private fun bankSnapshot(details: FinanceBankAccount?): Map<String, Any?> = if (details == null) emptyMap() else mapOf(
        "bankName" to details.bankName,
        "accountType" to details.accountType,
        "last4" to details.last4,
        "ifsc" to details.ifsc,
    )

    private fun AccountType.label() = when (this) {
        AccountType.ASSET -> "assets"
        AccountType.LIABILITY -> "liabilities"
        AccountType.FUND -> "funds"
        AccountType.INCOME -> "income"
        AccountType.EXPENSE -> "expenses"
    }

    private companion object {
        val CODE = Regex("^[A-Za-z0-9][A-Za-z0-9./-]{0,19}$")
        val LAST4 = Regex("^[0-9]{4}$")
        val IFSC = Regex("^[A-Z]{4}0[A-Z0-9]{6}$")
    }
}
