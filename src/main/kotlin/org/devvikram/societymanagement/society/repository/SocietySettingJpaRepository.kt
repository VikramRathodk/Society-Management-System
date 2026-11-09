package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.SocietySetting
import org.devvikram.societymanagement.society.model.SocietySettingId
import org.springframework.data.jpa.repository.JpaRepository

interface SocietySettingJpaRepository : JpaRepository<SocietySetting, SocietySettingId> {
    fun findBySocietyId(societyId: Long): List<SocietySetting>
}
