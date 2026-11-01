package org.devvikram.societymanagement.maintenance.repository

import org.devvikram.societymanagement.maintenance.model.MaintenanceSettings
import org.springframework.stereotype.Repository

@Repository
class MaintenanceSettingsRepositoryImpl(
    private val settingsJpaRepository: MaintenanceSettingsJpaRepository,
) : MaintenanceSettingsRepository {
    override fun findBySocietyId(societyId: Long): MaintenanceSettings? =
        settingsJpaRepository.findWithDetailsBySocietyId(societyId)

    override fun save(settings: MaintenanceSettings): MaintenanceSettings = settingsJpaRepository.save(settings)
}
