package org.devvikram.societymanagement.maintenance.repository

import org.devvikram.societymanagement.maintenance.model.ChargeHead
import org.springframework.stereotype.Repository

@Repository
class ChargeHeadRepositoryImpl(
    private val chargeHeadJpaRepository: ChargeHeadJpaRepository,
) : ChargeHeadRepository {
    override fun save(head: ChargeHead): ChargeHead = chargeHeadJpaRepository.save(head)

    override fun findById(id: Long): ChargeHead? = chargeHeadJpaRepository.findWithDetailsById(id)

    override fun findBySocietyId(societyId: Long): List<ChargeHead> = chargeHeadJpaRepository.findBySocietyId(societyId)

    override fun delete(head: ChargeHead) = chargeHeadJpaRepository.delete(head)
}
