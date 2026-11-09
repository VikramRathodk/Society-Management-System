package org.devvikram.societymanagement.finance.repository

import org.devvikram.societymanagement.finance.model.FinanceAccount
import org.devvikram.societymanagement.finance.model.FinanceBankAccount
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AccountJpaRepository : JpaRepository<FinanceAccount, Long> {
    fun findBySocietyIdOrderByCode(societyId: Long): List<FinanceAccount>

    fun existsBySocietyId(societyId: Long): Boolean

    fun findBySocietyIdAndSystemKey(societyId: Long, systemKey: String): FinanceAccount?

    @Query("select count(l) > 0 from VoucherLine l where l.accountId = :accountId")
    fun hasLines(@Param("accountId") accountId: Long): Boolean

    @Query(
        value = "select exists (select 1 from finance_charge_head_account where account_id = :accountId)",
        nativeQuery = true,
    )
    fun isMapped(@Param("accountId") accountId: Long): Boolean
}

interface BankAccountJpaRepository : JpaRepository<FinanceBankAccount, Long>
