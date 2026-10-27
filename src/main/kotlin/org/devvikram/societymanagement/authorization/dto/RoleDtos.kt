package org.devvikram.societymanagement.authorization.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.devvikram.societymanagement.authorization.model.Permission
import org.devvikram.societymanagement.authorization.model.RoleScope

data class CreateRoleRequest(
    @field:NotBlank val code: String,
    @field:NotBlank val name: String,
    val description: String? = null,
    @field:NotNull val scopeLevel: RoleScope,
    val requiresFlat: Boolean = false,
    val inherits: Set<String> = emptySet(),
    val includedIn: Set<String> = emptySet(),
    val permissions: Set<String> = emptySet(),
)

data class UpdateRoleRequest(
    val name: String? = null,
    val description: String? = null,
)

data class SetPermissionsRequest(val permissions: Set<String>)

data class SetInheritsRequest(val inherits: Set<String>)

data class RoleResponse(
    val code: String,
    val name: String,
    val description: String?,
    val scopeLevel: RoleScope,
    val requiresFlat: Boolean,
    val allPermissions: Boolean,
    val isSystem: Boolean,
    val inherits: List<String>,
    // Direct grants only; effectivePermissions adds everything inherited.
    val permissions: List<String>,
    val effectivePermissions: List<String>,
)

data class PermissionResponse(val code: String, val description: String?) {
    companion object {
        fun from(p: Permission) = PermissionResponse(p.code, p.description)
    }
}

data class AssignableRoleResponse(val code: String, val name: String, val scopeLevel: RoleScope, val requiresFlat: Boolean)
