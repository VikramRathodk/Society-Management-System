package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.SocietySetting

interface SocietySettingRepository {
    fun findBySociety(societyId: Long): List<SocietySetting>
    fun save(setting: SocietySetting): SocietySetting
    // Back to the default; nothing happens if the society never set it.
    fun delete(societyId: Long, key: String)
}
