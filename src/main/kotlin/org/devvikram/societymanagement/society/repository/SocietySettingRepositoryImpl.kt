package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.SocietySetting
import org.devvikram.societymanagement.society.model.SocietySettingId
import org.springframework.stereotype.Repository

@Repository
class SocietySettingRepositoryImpl(
    private val jpaRepository: SocietySettingJpaRepository,
) : SocietySettingRepository {
    override fun findBySociety(societyId: Long): List<SocietySetting> = jpaRepository.findBySocietyId(societyId)

    override fun save(setting: SocietySetting): SocietySetting = jpaRepository.save(setting)

    override fun delete(societyId: Long, key: String) {
        jpaRepository.deleteById(SocietySettingId(societyId, key))
    }
}
