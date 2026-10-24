package org.devvikram.societymanagement.society.domain


interface ComplexRepository {
    fun save(complex: Complex): Complex
    fun findById(id: Long): Complex?
    fun findAll(): List<Complex>
}
