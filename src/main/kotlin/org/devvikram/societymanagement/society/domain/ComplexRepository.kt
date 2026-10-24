package org.devvikram.societymanagement.society.domain


interface ComplexRepository {
    fun save(complex: Complex): Complex
    fun findById(id: Long): Complex?
    fun findAll(): List<Complex>
    // Applied immediately in the database, so a complex that societies still reference fails here, not at commit.
    fun delete(complex: Complex)
}
