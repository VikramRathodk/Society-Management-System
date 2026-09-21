package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.Complex
import org.devvikram.societymanagement.society.domain.ComplexRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ComplexRepositoryImpl(
    private val complexJpaRepository: ComplexJpaRepository,
) : ComplexRepository {
    override fun save(complex: Complex): Complex = complexJpaRepository.save(complex)

    override fun findById(id: UUID): Complex? = complexJpaRepository.findById(id).orElse(null)

    override fun findAll(): List<Complex> = complexJpaRepository.findAll()
}
