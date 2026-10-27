package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.Flat
import org.springframework.stereotype.Repository

@Repository
class FlatRepositoryImpl(
    private val flatJpaRepository: FlatJpaRepository,
) : FlatRepository {
    override fun save(flat: Flat): Flat = flatJpaRepository.save(flat)

    override fun findById(id: Long): Flat? = flatJpaRepository.findById(id).orElse(null)

    override fun findAll(): List<Flat> = flatJpaRepository.findAll()

    override fun findByWingId(wingId: Long): List<Flat> = flatJpaRepository.findByWingId(wingId)

    override fun deleteById(id: Long) = flatJpaRepository.deleteById(id)
}
