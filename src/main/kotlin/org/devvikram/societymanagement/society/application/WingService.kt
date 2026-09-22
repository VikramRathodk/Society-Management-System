package org.devvikram.societymanagement.society.application

import org.devvikram.societymanagement.society.domain.SocietyRepository
import org.devvikram.societymanagement.society.domain.Wing
import org.devvikram.societymanagement.society.domain.WingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class WingService(
    private val wingRepository: WingRepository,
    private val societyRepository: SocietyRepository,
) {
    @Transactional
    fun addWing(societyId: UUID, name: String): Wing {
        val society = societyRepository.findById(societyId)
            ?: throw NoSuchElementException("Society $societyId not found")
        return wingRepository.save(Wing(society = society, name = name))
    }

    fun getById(id: UUID): Wing =
        wingRepository.findById(id) ?: throw NoSuchElementException("Wing $id not found")

    fun listBySociety(societyId: UUID): List<Wing> = wingRepository.findBySocietyId(societyId)

    @Transactional
    fun renameWing(id: UUID, name: String): Wing {
        val wing = getById(id)
        wing.name = name
        return wingRepository.save(wing)
    }

    @Transactional
    fun deleteWing(id: UUID) {
        getById(id)
        wingRepository.deleteById(id)
    }
}
