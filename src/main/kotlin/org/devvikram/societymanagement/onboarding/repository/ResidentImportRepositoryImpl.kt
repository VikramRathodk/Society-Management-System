package org.devvikram.societymanagement.onboarding.repository

import org.devvikram.societymanagement.onboarding.model.ResidentImport
import org.devvikram.societymanagement.onboarding.model.ResidentImportStatus
import org.springframework.stereotype.Repository

@Repository
class ResidentImportRepositoryImpl(private val jpaRepository: ResidentImportJpaRepository) : ResidentImportRepository {
    override fun save(residentImport: ResidentImport): ResidentImport = jpaRepository.save(residentImport)

    override fun delete(residentImport: ResidentImport) = jpaRepository.delete(residentImport)

    override fun flush() = jpaRepository.flush()

    override fun findOpen(societyId: Long): ResidentImport? =
        jpaRepository.findFirstBySocietyIdAndStatus(societyId, ResidentImportStatus.OPEN)
}
