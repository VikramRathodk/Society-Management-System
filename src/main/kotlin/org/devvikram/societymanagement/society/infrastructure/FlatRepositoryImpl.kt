package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.Flat
import org.devvikram.societymanagement.society.domain.FlatRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class FlatRepositoryImpl(
    private val flatJpaRepository: FlatJpaRepository,
) : FlatRepository {
    override fun save(flat: Flat): Flat = flatJpaRepository.save(flat)

    override fun findById(id: UUID): Flat? = flatJpaRepository.findById(id).orElse(null)

    override fun findAll(): List<Flat> = flatJpaRepository.findAll()
}
