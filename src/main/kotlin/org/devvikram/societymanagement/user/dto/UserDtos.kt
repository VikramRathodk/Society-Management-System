package org.devvikram.societymanagement.user.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.user.model.ResidentType
import org.devvikram.societymanagement.user.model.User
import org.devvikram.societymanagement.user.model.UserRole
import org.devvikram.societymanagement.user.service.AssignRoleCommand

// role is a role code (e.g. "RESIDENT", "SECRETARY", or any custom role a SUPER_ADMIN defined).
data class CreateUserRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val phone: String,
    val email: String? = null,
    @field:NotBlank val password: String,
    @field:NotBlank val role: String,
    val societyId: Long? = null,
    val flatId: Long? = null,
    val residentType: ResidentType? = null,
) {
    fun roleCommand() = AssignRoleCommand(role, societyId, flatId, residentType)
}

data class AssignRoleRequest(
    @field:NotBlank val role: String,
    val societyId: Long? = null,
    val flatId: Long? = null,
    val residentType: ResidentType? = null,
) {
    fun toCommand() = AssignRoleCommand(role, societyId, flatId, residentType)
}

data class UpdateProfileRequest(
    val name: String? = null,
    val email: String? = null,
    val fcmToken: String? = null,
)

// BCrypt only reads the first 72 bytes, so a longer password would silently be truncated.
data class ChangePasswordRequest(
    @field:NotBlank val currentPassword: String,
    @field:NotBlank @field:Size(min = 8, max = 72) val newPassword: String,
)

data class RoleAssignmentResponse(
    val id: Long,
    val role: String,
    val roleName: String,
    val societyId: Long?,
    val flatId: Long?,
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

data class UserResponse(
    val id: Long,
    val name: String,
    val phone: String,
    val email: String?,
    val isActive: Boolean,
    val roles: List<RoleAssignmentResponse>,
) {
    companion object {
        fun from(user: User, roles: List<UserRole>) = UserResponse(
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
    val societyId: Long,
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
    val user: UserResponse,
    val access: AccessResponse,
)
