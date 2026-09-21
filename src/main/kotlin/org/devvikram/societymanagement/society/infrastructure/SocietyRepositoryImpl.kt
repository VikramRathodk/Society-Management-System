package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.Society
import org.devvikram.societymanagement.society.domain.SocietyRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class SocietyRepositoryImpl(
    private val societyJpaRepository: SocietyJpaRepository,
) : SocietyRepository {
    override fun save(society: Society): Society = societyJpaRepository.save(society)

    override fun findById(id: UUID): Society? = societyJpaRepository.findById(id).orElse(null)

    override fun findAll(): List<Society> = societyJpaRepository.findAll()
}
