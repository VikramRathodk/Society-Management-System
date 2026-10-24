package org.devvikram.societymanagement.society.application

import org.devvikram.societymanagement.society.domain.SocietyRepository
import org.devvikram.societymanagement.society.domain.Wing
import org.devvikram.societymanagement.society.domain.WingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WingService(
    private val wingRepository: WingRepository,
    private val societyRepository: SocietyRepository,
) {
    @Transactional
    fun addWing(societyId: Long, name: String): Wing {
        val society = societyRepository.findById(societyId)
            ?: throw NoSuchElementException("Society $societyId not found")
        return wingRepository.save(Wing(society = society, name = name))
    }

    fun getById(id: Long): Wing =
        wingRepository.findById(id) ?: throw NoSuchElementException("Wing $id not found")

    // Read inside a transaction: wing.society is lazy and open-in-view is off.
    @Transactional(readOnly = true)
    fun societyIdOf(wingId: Long): Long = getById(wingId).society.id!!

    fun listBySociety(societyId: Long): List<Wing> = wingRepository.findBySocietyId(societyId)

    @Transactional
    fun renameWing(id: Long, name: String): Wing {
        val wing = getById(id)
        wing.name = name
        return wingRepository.save(wing)
    }

    @Transactional
    fun deleteWing(id: Long) {
        getById(id)
        wingRepository.deleteById(id)
    }
}
