package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.Wing
import org.devvikram.societymanagement.society.domain.WingRepository
import org.springframework.stereotype.Repository

@Repository
class WingRepositoryImpl(
    private val wingJpaRepository: WingJpaRepository,
) : WingRepository {
    override fun save(wing: Wing): Wing = wingJpaRepository.save(wing)

    override fun findById(id: Long): Wing? = wingJpaRepository.findById(id).orElse(null)

    override fun findAll(): List<Wing> = wingJpaRepository.findAll()

    override fun findBySocietyId(societyId: Long): List<Wing> = wingJpaRepository.findBySocietyId(societyId)

    override fun deleteById(id: Long) = wingJpaRepository.deleteById(id)
}
