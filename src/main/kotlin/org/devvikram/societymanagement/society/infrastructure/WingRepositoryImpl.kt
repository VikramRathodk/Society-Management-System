package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.Wing
import org.devvikram.societymanagement.society.domain.WingRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class WingRepositoryImpl(
    private val wingJpaRepository: WingJpaRepository,
) : WingRepository {
    override fun save(wing: Wing): Wing = wingJpaRepository.save(wing)

    override fun findById(id: UUID): Wing? = wingJpaRepository.findById(id).orElse(null)

    override fun findAll(): List<Wing> = wingJpaRepository.findAll()

    override fun findBySocietyId(societyId: UUID): List<Wing> = wingJpaRepository.findBySocietyId(societyId)

    override fun deleteById(id: UUID) = wingJpaRepository.deleteById(id)
}
