package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.finance.model.AccountType
import org.devvikram.societymanagement.finance.model.AuditAction
import org.devvikram.societymanagement.finance.model.AuditEntityType
import org.devvikram.societymanagement.finance.model.ChargeHeadAccount
import org.devvikram.societymanagement.finance.model.FinanceAccount
import org.devvikram.societymanagement.finance.model.SystemAccounts
import org.devvikram.societymanagement.finance.repository.BooksRepository
import org.devvikram.societymanagement.finance.repository.ChargeHeadAccountRepository
import org.devvikram.societymanagement.maintenance.model.ChargeHead
import org.devvikram.societymanagement.maintenance.service.MaintenanceConfigService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// A charge head and the ledger its bill lines credit. `mapped = false` means the default (Maintenance
// charges) applies: the screen shows those with a warning.
data class ChargeHeadMapping(val head: ChargeHead, val account: FinanceAccount, val mapped: Boolean)

// Which ledger each maintenance charge head credits. A sinking-fund head should credit the Sinking fund
// (a FUND), not income: that's why this is a mapping. Changes apply to bills issued afterwards.
@Service
class ChargeHeadMappingService(
    private val repository: ChargeHeadAccountRepository,
    private val configService: MaintenanceConfigService,
    private val chartService: ChartService,
    private val booksRepository: BooksRepository,
    private val audit: FinanceAudit,
) {
    fun mappings(societyId: Long): List<ChargeHeadMapping> {
        val heads = configService.chargeHeads(societyId)
        val stored = repository.findByChargeHeadIds(heads.map { it.id!! }).associateBy { it.chargeHeadId }
        val accounts = chartService.accounts(societyId).associateBy { it.id!! }
        val default = chartService.systemAccount(societyId, SystemAccounts.MAINTENANCE_INCOME)
        return heads.map { head ->
            val mapped = stored[head.id]?.let { accounts[it.accountId] }
            ChargeHeadMapping(head, mapped ?: default, mapped != null)
        }
    }

    // The credit ledger per head name, as bills copy head names when they are issued.
    fun accountsByHeadName(societyId: Long): Map<String, Long> =
        mappings(societyId).associate { it.head.name.lowercase() to it.account.id!! }

    // Sets (accountId) or clears (null) each listed head's ledger. All or nothing.
    @Transactional
    fun update(societyId: Long, changes: Map<Long, Long?>, userId: Long) {
        booksRepository.lockSociety(societyId)
        val heads = configService.chargeHeads(societyId).associateBy { it.id!! }
        val unknown = changes.keys - heads.keys
        require(unknown.isEmpty()) { "Charge head ${unknown.first()} is not in this society" }
        val money = chartService.moneyAccountIds(societyId)
        changes.values.filterNotNull().distinct().forEach { accountId ->
            val account = chartService.requirePostable(societyId, accountId)
            require(account.type in CREDIT_TYPES) { "${account.name} is not an income, fund or liability account" }
            require(account.id !in money && account.systemKey !in SystemAccounts.PER_FLAT) {
                "${account.name} can't take maintenance charges"
            }
        }
        val before = repository.findByChargeHeadIds(changes.keys).associate { it.chargeHeadId to it.accountId }
        val after = mutableMapOf<Long, Long?>()
        for ((headId, accountId) in changes) {
            if (before[headId] == accountId) continue
            if (accountId == null) repository.delete(headId) else repository.save(ChargeHeadAccount(headId, accountId))
            after[headId] = accountId
        }
        if (after.isEmpty()) return
        audit.record(societyId, AuditEntityType.MAPPING, null, AuditAction.UPDATE, userId,
            oldValues = after.keys.associate { heads.getValue(it).name to before[it] },
            newValues = after.mapKeys { (headId, _) -> heads.getValue(headId).name })
    }

    private companion object {
        val CREDIT_TYPES = setOf(AccountType.INCOME, AccountType.FUND, AccountType.LIABILITY)
    }
}
