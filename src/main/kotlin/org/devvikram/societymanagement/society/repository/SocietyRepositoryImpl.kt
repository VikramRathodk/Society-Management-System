package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.Society
import org.springframework.stereotype.Repository

@Repository
class SocietyRepositoryImpl(
    private val societyJpaRepository: SocietyJpaRepository,
) : SocietyRepository {
    override fun save(society: Society): Society = societyJpaRepository.save(society)

    override fun findById(id: Long): Society? = societyJpaRepository.findById(id).orElse(null)

    override fun findAll(): List<Society> = societyJpaRepository.findAll()

    override fun delete(society: Society) {
        societyJpaRepository.deleteDirectly(society.id!!)
    }
}
