package org.devvikram.societymanagement.maintenance.repository

import org.devvikram.societymanagement.maintenance.model.MaintenanceSettings

interface MaintenanceSettingsRepository {
    // null when the society never saved its settings (the defaults apply).
    fun findBySocietyId(societyId: Long): MaintenanceSettings?
    fun save(settings: MaintenanceSettings): MaintenanceSettings
}
