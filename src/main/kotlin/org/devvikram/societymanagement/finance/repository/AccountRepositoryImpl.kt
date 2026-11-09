package org.devvikram.societymanagement.finance.repository

import org.devvikram.societymanagement.finance.model.FinanceAccount
import org.devvikram.societymanagement.finance.model.FinanceBankAccount
import org.springframework.stereotype.Repository

@Repository
class AccountRepositoryImpl(
    private val accountJpaRepository: AccountJpaRepository,
    private val bankAccountJpaRepository: BankAccountJpaRepository,
) : AccountRepository {
    override fun save(account: FinanceAccount): FinanceAccount = accountJpaRepository.save(account)

    override fun findById(id: Long): FinanceAccount? = accountJpaRepository.findById(id).orElse(null)

    override fun findBySocietyId(societyId: Long): List<FinanceAccount> =
        accountJpaRepository.findBySocietyIdOrderByCode(societyId)

    override fun existsBySocietyId(societyId: Long): Boolean = accountJpaRepository.existsBySocietyId(societyId)

    override fun findBySystemKey(societyId: Long, systemKey: String): FinanceAccount? =
        accountJpaRepository.findBySocietyIdAndSystemKey(societyId, systemKey)

    override fun delete(account: FinanceAccount) {
        bankAccountJpaRepository.findById(account.id!!).ifPresent(bankAccountJpaRepository::delete)
        accountJpaRepository.delete(account)
    }

    override fun hasLines(accountId: Long): Boolean = accountJpaRepository.hasLines(accountId)

    override fun isMapped(accountId: Long): Boolean = accountJpaRepository.isMapped(accountId)

    override fun saveBank(details: FinanceBankAccount): FinanceBankAccount = bankAccountJpaRepository.save(details)

    override fun findBank(accountId: Long): FinanceBankAccount? = bankAccountJpaRepository.findById(accountId).orElse(null)

    override fun findBanks(accountIds: Collection<Long>): List<FinanceBankAccount> =
        if (accountIds.isEmpty()) emptyList() else bankAccountJpaRepository.findAllById(accountIds)
}
