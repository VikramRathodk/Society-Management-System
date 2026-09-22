package org.devvikram.societymanagement.society.application

import org.devvikram.societymanagement.society.domain.ComplexRepository
import org.devvikram.societymanagement.society.domain.Society
import org.devvikram.societymanagement.society.domain.SocietyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class SocietyService(
    private val societyRepository: SocietyRepository,
    private val complexRepository: ComplexRepository,
) {
    @Transactional
    fun createSociety(name: String, address: String? = null, complexId: UUID? = null): Society {
        val complex = complexId?.let {
            complexRepository.findById(it) ?: throw NoSuchElementException("Complex $it not found")
        }
        return societyRepository.save(Society(name = name, address = address, complex = complex))
    }

    fun getById(id: UUID): Society =
        societyRepository.findById(id) ?: throw NoSuchElementException("Society $id not found")

    fun getAll(): List<Society> = societyRepository.findAll()

    @Transactional
    fun updateSettings(id: UUID, name: String? = null, address: String? = null): Society {
        val society = getById(id)
        name?.let { society.name = it }
        address?.let { society.address = it }
        return societyRepository.save(society)
    }
}
