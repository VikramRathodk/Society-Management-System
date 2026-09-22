package org.devvikram.societymanagement.society.application

import org.devvikram.societymanagement.society.domain.Complex
import org.devvikram.societymanagement.society.domain.ComplexRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// Reserved — no Complex Admin role confirmed yet (see society_planning.md Open Decisions).
// Kept minimal: create/read/rename, no delete.
@Service
class ComplexService(
    private val complexRepository: ComplexRepository,
) {
    @Transactional
    fun createComplex(name: String, address: String? = null): Complex =
        complexRepository.save(Complex(name = name, address = address))

    fun getById(id: UUID): Complex =
        complexRepository.findById(id) ?: throw NoSuchElementException("Complex $id not found")

    fun getAll(): List<Complex> = complexRepository.findAll()

    @Transactional
    fun updateSettings(id: UUID, name: String? = null, address: String? = null): Complex {
        val complex = getById(id)
        name?.let { complex.name = it }
        address?.let { complex.address = it }
        return complexRepository.save(complex)
    }
}
