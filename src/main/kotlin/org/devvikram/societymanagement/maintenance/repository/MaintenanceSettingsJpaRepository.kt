package org.devvikram.societymanagement.maintenance.repository

import org.devvikram.societymanagement.maintenance.model.MaintenanceSettings
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MaintenanceSettingsJpaRepository : JpaRepository<MaintenanceSettings, Long> {
    @Query("select s from MaintenanceSettings s left join fetch s.updatedBy where s.societyId = :societyId")
    fun findWithDetailsBySocietyId(@Param("societyId") societyId: Long): MaintenanceSettings?
}
