package org.devvikram.societymanagement.maintenance.service

import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.devvikram.societymanagement.user.model.ResidentType
import org.springframework.stereotype.Component

// Who sees a flat's maintenance (bills, payments, dues, bill notifications) through a role tied to the
// flat. Bills are the owner's: owners and family always see them, tenants only when the society turns on
// `tenantsSeeMaintenance`. A flat role without a resident type counts as the owner side. Society-wide
// roles (COMMITTEE and above) see every flat regardless.
@Component
class MaintenanceAudience(private val settings: SocietySettingsService) {

    fun tenantsIncluded(societyId: Long): Boolean =
        settings.boolean(societyId, SocietySettingKeys.TENANTS_SEE_MAINTENANCE)

    fun includes(residentType: String?, tenantsIncluded: Boolean): Boolean =
        tenantsIncluded || residentType != ResidentType.TENANT.name

    // Flats of the society whose records the user sees through their own flat-tied roles holding `permission`.
    fun ownFlats(access: Access, permission: String, societyId: Long): Set<Long> {
        val withPermission = access.flatsWith(permission)
        val tenants = tenantsIncluded(societyId)
        return access.grants
            .filter { it.societyId == societyId && it.flatId in withPermission && includes(it.residentType, tenants) }
            .mapNotNull { it.flatId }
            .toSet()
    }

    fun canSee(access: Access, permission: String, societyId: Long, flatId: Long): Boolean =
        access.canSocietyWide(permission, societyId) || flatId in ownFlats(access, permission, societyId)
}
