package org.devvikram.societymanagement.finance.repository

import org.devvikram.societymanagement.finance.model.FinanceAccount
import org.devvikram.societymanagement.finance.model.FinanceBankAccount

// The chart of accounts and the bank details of bank ledgers.
interface AccountRepository {
    fun save(account: FinanceAccount): FinanceAccount
    fun findById(id: Long): FinanceAccount?
    // The whole chart of a society, by code.
    fun findBySocietyId(societyId: Long): List<FinanceAccount>
    fun existsBySocietyId(societyId: Long): Boolean
    fun findBySystemKey(societyId: Long, systemKey: String): FinanceAccount?
    fun delete(account: FinanceAccount)
    // Any line at all, in any voucher status.
    fun hasLines(accountId: Long): Boolean
    // Mapped from a maintenance charge head.
    fun isMapped(accountId: Long): Boolean

    fun saveBank(details: FinanceBankAccount): FinanceBankAccount
    fun findBank(accountId: Long): FinanceBankAccount?
    fun findBanks(accountIds: Collection<Long>): List<FinanceBankAccount>
}
