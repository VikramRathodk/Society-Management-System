package org.devvikram.societymanagement.society.application

import org.devvikram.societymanagement.society.domain.Flat
import org.devvikram.societymanagement.society.domain.FlatRepository
import org.devvikram.societymanagement.society.domain.WingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class FlatService(
    private val flatRepository: FlatRepository,
    private val wingRepository: WingRepository,
) {
    @Transactional
    fun addFlat(wingId: UUID, flatNumber: String): Flat {
        val wing = wingRepository.findById(wingId)
            ?: throw NoSuchElementException("Wing $wingId not found")
        require(flatRepository.findByWingId(wingId).none { it.flatNumber == flatNumber }) {
            "Flat $flatNumber already exists in this wing"
        }
        return flatRepository.save(Flat(wing = wing, flatNumber = flatNumber))
    }

    fun getById(id: UUID): Flat =
        flatRepository.findById(id) ?: throw NoSuchElementException("Flat $id not found")

    fun listByWing(wingId: UUID): List<Flat> = flatRepository.findByWingId(wingId)

    @Transactional
    fun updateFlatNumber(id: UUID, flatNumber: String): Flat {
        val flat = getById(id)
        require(flatRepository.findByWingId(flat.wing.id!!).none { it.id != flat.id && it.flatNumber == flatNumber }) {
            "Flat $flatNumber already exists in this wing"
        }
        flat.flatNumber = flatNumber
        return flatRepository.save(flat)
    }

    @Transactional
    fun deleteFlat(id: UUID) {
        getById(id)
        flatRepository.deleteById(id)
    }
}
