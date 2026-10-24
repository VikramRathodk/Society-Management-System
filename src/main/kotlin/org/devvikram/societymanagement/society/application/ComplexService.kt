package org.devvikram.societymanagement.society.application

import org.devvikram.societymanagement.common.ConflictException
import org.devvikram.societymanagement.society.domain.Complex
import org.devvikram.societymanagement.society.domain.ComplexRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// Reserved — no Complex Admin role confirmed yet (see society_planning.md Open Decisions).
// Kept minimal: create/read/rename, and delete while no society belongs to it.
@Service
class ComplexService(
    private val complexRepository: ComplexRepository,
) {
    @Transactional
    fun createComplex(name: String, address: String? = null): Complex =
        complexRepository.save(Complex(name = name, address = address))

    fun getById(id: Long): Complex =
        complexRepository.findById(id) ?: throw NoSuchElementException("Complex $id not found")

    fun getAll(): List<Complex> = complexRepository.findAll()

    @Transactional
    fun updateSettings(id: Long, name: String? = null, address: String? = null): Complex {
        val complex = getById(id)
        name?.let { complex.name = it }
        address?.let { complex.address = it }
        return complexRepository.save(complex)
    }

    @Transactional
    fun delete(id: Long) {
        val complex = getById(id)
        try {
            complexRepository.delete(complex)
        } catch (e: DataIntegrityViolationException) {
            throw ConflictException("Complex ${complex.name} still has societies and cannot be deleted")
        }
    }
}
