package org.devvikram.societymanagement.society.domain

import java.util.UUID

interface ComplexRepository {
    fun save(complex: Complex): Complex
    fun findById(id: UUID): Complex?
    fun findAll(): List<Complex>
}
