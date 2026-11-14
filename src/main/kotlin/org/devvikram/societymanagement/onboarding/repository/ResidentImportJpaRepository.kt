package org.devvikram.societymanagement.onboarding.repository

import org.devvikram.societymanagement.onboarding.model.ResidentImport
import org.devvikram.societymanagement.onboarding.model.ResidentImportStatus
import org.springframework.data.jpa.repository.JpaRepository

interface ResidentImportJpaRepository : JpaRepository<ResidentImport, Long> {
    fun findFirstBySocietyIdAndStatus(societyId: Long, status: ResidentImportStatus): ResidentImport?
}
