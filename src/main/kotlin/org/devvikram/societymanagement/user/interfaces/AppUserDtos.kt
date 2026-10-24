package org.devvikram.societymanagement.user.interfaces

import jakarta.validation.constraints.NotBlank
import org.devvikram.societymanagement.security.Access
import org.devvikram.societymanagement.user.application.AssignRoleCommand
import org.devvikram.societymanagement.user.domain.AppUser
import org.devvikram.societymanagement.user.domain.ResidentType
import org.devvikram.societymanagement.user.domain.UserRole
import java.util.UUID

// role is a role code (e.g. "RESIDENT", "SECRETARY", or any custom role a SUPER_ADMIN defined).
data class CreateAppUserRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val phone: String,
    val email: String? = null,
    @field:NotBlank val password: String,
    @field:NotBlank val role: String,
    val societyId: UUID? = null,
    val flatId: UUID? = null,
    val residentType: ResidentType? = null,
) {
    fun roleCommand() = AssignRoleCommand(role, societyId, flatId, residentType)
}

data class AssignRoleRequest(
    @field:NotBlank val role: String,
    val societyId: UUID? = null,
    val flatId: UUID? = null,
    val residentType: ResidentType? = null,
) {
    fun toCommand() = AssignRoleCommand(role, societyId, flatId, residentType)
}

data class UpdateProfileRequest(
    val name: String? = null,
    val email: String? = null,
    val fcmToken: String? = null,
)

data class RoleAssignmentResponse(
    val id: UUID,
    val role: String,
    val roleName: String,
    val societyId: UUID?,
    val flatId: UUID?,
    val residentType: ResidentType?,
) {
    companion object {
        fun from(userRole: UserRole) = RoleAssignmentResponse(
            id = userRole.id!!,
            role = userRole.role.code,
            roleName = userRole.role.name,
            societyId = userRole.society?.id,
            flatId = userRole.flat?.id,
            residentType = userRole.residentType,
        )
    }
}

data class AppUserResponse(
    val id: UUID,
    val name: String,
    val phone: String,
    val email: String?,
    val isActive: Boolean,
    val roles: List<RoleAssignmentResponse>,
) {
    companion object {
        fun from(user: AppUser, roles: List<UserRole>) = AppUserResponse(
            id = user.id!!,
            name = user.name,
            phone = user.phone,
            email = user.email,
            isActive = user.isActive,
            roles = roles.map(RoleAssignmentResponse::from),
        )
    }
}

data class SocietyAccessResponse(
    val societyId: UUID,
    val roles: List<String>,
    val permissions: Set<String>,
)

// Effective permissions per scope, for the client to drive role-based UI.
data class AccessResponse(
    val platformRoles: List<String>,
    val platformPermissions: Set<String>,
    val societies: List<SocietyAccessResponse>,
) {
    companion object {
        fun from(access: Access) = AccessResponse(
            platformRoles = access.grants.filter { it.societyId == null }.map { it.roleCode }.distinct(),
            platformPermissions = access.platformPermissions,
            societies = access.societyPermissions.map { (societyId, permissions) ->
                SocietyAccessResponse(
                    societyId = societyId,
                    roles = access.grants.filter { it.societyId == societyId }.map { it.roleCode }.distinct(),
                    permissions = permissions,
                )
            },
        )
    }
}

data class MeResponse(
    val user: AppUserResponse,
    val access: AccessResponse,
)
