package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.Complex
import org.springframework.stereotype.Repository

@Repository
class ComplexRepositoryImpl(
    private val complexJpaRepository: ComplexJpaRepository,
) : ComplexRepository {
    override fun save(complex: Complex): Complex = complexJpaRepository.save(complex)

    override fun findById(id: Long): Complex? = complexJpaRepository.findById(id).orElse(null)

    override fun findAll(): List<Complex> = complexJpaRepository.findAll()

    override fun delete(complex: Complex) {
        complexJpaRepository.deleteDirectly(complex.id!!)
    }
}
