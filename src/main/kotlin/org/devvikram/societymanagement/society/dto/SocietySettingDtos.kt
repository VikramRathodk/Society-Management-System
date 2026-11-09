package org.devvikram.societymanagement.society.dto

import org.devvikram.societymanagement.society.service.SettingGroup
import org.devvikram.societymanagement.society.service.SettingType
import org.devvikram.societymanagement.society.service.SettingValue
import java.time.Instant

// Everything the settings page needs to draw itself: groups in order, and per setting its type,
// limits, value and default. Values are JSON booleans, numbers or strings according to `type`.
data class SocietySettingsResponse(
    val societyId: Long,
    val groups: List<SettingGroupResponse>,
) {
    companion object {
        fun from(societyId: Long, values: List<SettingValue>): SocietySettingsResponse {
            val byGroup = values.groupBy { it.definition.group }
            return SocietySettingsResponse(
                societyId = societyId,
                groups = SettingGroup.entries.mapNotNull { group ->
                    byGroup[group]?.let { SettingGroupResponse(group.name, group.label, it.map(SettingResponse::from)) }
                },
            )
        }
    }
}

data class SettingGroupResponse(
    val key: String,
    val label: String,
    val settings: List<SettingResponse>,
)

data class SettingResponse(
    val key: String,
    val label: String,
    val description: String,
    val type: SettingType,
    val value: Any,
    val defaultValue: Any,
    val customized: Boolean,
    val min: Long?,
    val max: Long?,
    val maxLength: Int?,
    val options: List<String>,
    val updatedAt: Instant?,
    val updatedById: Long?,
) {
    companion object {
        fun from(setting: SettingValue): SettingResponse {
            val d = setting.definition
            return SettingResponse(
                key = d.key,
                label = d.label,
                description = d.description,
                type = d.type,
                value = d.typed(setting.value),
                defaultValue = d.typed(d.defaultValue),
                customized = setting.customized,
                min = d.min,
                max = d.max,
                maxLength = d.maxLength,
                options = d.options,
                updatedAt = setting.updatedAt,
                updatedById = setting.updatedBy,
            )
        }
    }
}
