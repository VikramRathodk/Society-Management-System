package org.devvikram.societymanagement.invitation.service

import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.model.Role
import org.devvikram.societymanagement.authorization.repository.RoleRepository
import org.devvikram.societymanagement.society.repository.FlatRepository
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsService
import org.devvikram.societymanagement.user.model.ResidentType
import org.springframework.stereotype.Component

// How someone may invite: by rank (STAFF: USER.INVITE.CREATE and a role above the invited one), or as a
// resident onto their own flat (RESIDENT).
enum class InviteRoute { STAFF, RESIDENT }

// A flat a resident may invite people to, and as what.
data class FlatInviteOption(
    val flatId: Long,
    val flatLabel: String,
    val roleCode: String,
    val roleName: String,
    val residentTypes: List<ResidentType>,
    val needsApproval: Boolean,
)

// Residents: when the society allows it, someone holding a flat role may invite to that same role on that
// same flat: an owner (or a flat role without a type) brings in family and tenants, a tenant brings in
// other tenants of their household, family members invite nobody.
@Component
class InvitationRules(
    private val settings: SocietySettingsService,
    private val roleRepository: RoleRepository,
    private val flatRepository: FlatRepository,
) {

    fun route(actor: Access, role: Role, societyId: Long, flatId: Long?, residentType: ResidentType?): InviteRoute? = when {
        actor.canGrant(Permissions.USER_INVITE_CREATE, role.code, societyId) -> InviteRoute.STAFF
        residentMayInvite(actor, role, societyId, flatId, residentType) -> InviteRoute.RESIDENT
        else -> null
    }

    fun needsApproval(route: InviteRoute, societyId: Long): Boolean =
        route == InviteRoute.RESIDENT && settings.boolean(societyId, SocietySettingKeys.RESIDENT_INVITES_NEED_APPROVAL)

    // Every flat of the society the actor may invite residents to as a resident (empty when not allowed).
    fun residentOptions(actor: Access, societyId: Long): List<FlatInviteOption> {
        if (!settings.boolean(societyId, SocietySettingKeys.RESIDENTS_CAN_INVITE)) return emptyList()
        val approval = needsApproval(InviteRoute.RESIDENT, societyId)
        return actor.grants
            .filter { it.societyId == societyId && it.flatId != null }
            .mapNotNull { grant ->
                val types = typesInvitableBy(grant.residentType)
                if (types.isEmpty()) return@mapNotNull null
                val role = roleRepository.findByCode(grant.roleCode) ?: return@mapNotNull null
                val flat = flatRepository.findById(grant.flatId!!) ?: return@mapNotNull null
                FlatInviteOption(flat.id!!, "${flat.wing.name}-${flat.flatNumber}", role.code, role.name, types, approval)
            }
            .groupBy { it.flatId to it.roleCode }
            .map { (_, options) -> options.first().copy(residentTypes = options.flatMap { it.residentTypes }.distinct().sorted()) }
            .sortedBy { it.flatLabel }
    }

    private fun residentMayInvite(
        actor: Access, role: Role, societyId: Long, flatId: Long?, residentType: ResidentType?,
    ): Boolean {
        if (!role.requiresFlat || flatId == null || residentType == null) return false
        if (!settings.boolean(societyId, SocietySettingKeys.RESIDENTS_CAN_INVITE)) return false
        return actor.grants.any {
            it.societyId == societyId && it.flatId == flatId && it.roleCode == role.code &&
                residentType in typesInvitableBy(it.residentType)
        }
    }

    companion object {
        fun typesInvitableBy(residentType: String?): List<ResidentType> = when (residentType) {
            null, ResidentType.OWNER.name -> listOf(ResidentType.FAMILY, ResidentType.TENANT)
            ResidentType.TENANT.name -> listOf(ResidentType.TENANT)
            else -> emptyList()
        }
    }
}
