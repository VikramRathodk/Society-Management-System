package org.devvikram.societymanagement.finance.repository

import org.devvikram.societymanagement.finance.model.ChargeHeadAccount
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

interface ChargeHeadAccountRepository {
    fun findByChargeHeadIds(ids: Collection<Long>): List<ChargeHeadAccount>
    fun save(mapping: ChargeHeadAccount): ChargeHeadAccount
    fun delete(chargeHeadId: Long)
}

interface ChargeHeadAccountJpaRepository : JpaRepository<ChargeHeadAccount, Long>

@Repository
class ChargeHeadAccountRepositoryImpl(private val jpa: ChargeHeadAccountJpaRepository) : ChargeHeadAccountRepository {
    override fun findByChargeHeadIds(ids: Collection<Long>): List<ChargeHeadAccount> =
        if (ids.isEmpty()) emptyList() else jpa.findAllById(ids)

    override fun save(mapping: ChargeHeadAccount): ChargeHeadAccount = jpa.save(mapping)

    override fun delete(chargeHeadId: Long) {
        if (jpa.existsById(chargeHeadId)) jpa.deleteById(chargeHeadId)
    }
}
