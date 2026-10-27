package org.devvikram.societymanagement.society.service

import org.devvikram.societymanagement.society.model.Flat
import org.devvikram.societymanagement.society.repository.FlatRepository
import org.devvikram.societymanagement.society.repository.WingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FlatService(
    private val flatRepository: FlatRepository,
    private val wingRepository: WingRepository,
) {
    @Transactional
    fun addFlat(wingId: Long, flatNumber: String): Flat {
        val wing = wingRepository.findById(wingId)
            ?: throw NoSuchElementException("Wing $wingId not found")
        require(flatRepository.findByWingId(wingId).none { it.flatNumber == flatNumber }) {
            "Flat $flatNumber already exists in this wing"
        }
        return flatRepository.save(Flat(wing = wing, flatNumber = flatNumber))
    }

    fun getById(id: Long): Flat =
        flatRepository.findById(id) ?: throw NoSuchElementException("Flat $id not found")

    // Read inside a transaction: flat.wing and wing.society are lazy and open-in-view is off.
    @Transactional(readOnly = true)
    fun societyIdOf(flatId: Long): Long = getById(flatId).wing.society.id!!

    fun listByWing(wingId: Long): List<Flat> = flatRepository.findByWingId(wingId)

    @Transactional
    fun updateFlatNumber(id: Long, flatNumber: String): Flat {
        val flat = getById(id)
        require(flatRepository.findByWingId(flat.wing.id!!).none { it.id != flat.id && it.flatNumber == flatNumber }) {
            "Flat $flatNumber already exists in this wing"
        }
        flat.flatNumber = flatNumber
        return flatRepository.save(flat)
    }

    @Transactional
    fun deleteFlat(id: Long) {
        getById(id)
        flatRepository.deleteById(id)
    }
}
