package org.devvikram.societymanagement.security.role

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.devvikram.societymanagement.security.AccessService
import org.devvikram.societymanagement.security.PermissionService
import org.devvikram.societymanagement.security.RequiresPermission
import org.devvikram.societymanagement.security.permission.Permission
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

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

// Role definitions are platform-wide, so every write needs the permission at PLATFORM scope
// (i.e. a SUPER_ADMIN), not merely in some society.
@RestController
class RoleController(
    private val roleService: RoleService,
    private val permissionService: PermissionService,
    private val accessService: AccessService,
) {

    @GetMapping("/api/permissions")
    @RequiresPermission("SYSTEM.PERMISSION.VIEW")
    fun permissions(): List<PermissionResponse> {
        accessService.current().requireOnPlatform("SYSTEM.PERMISSION.VIEW")
        return roleService.getAllPermissions().map(PermissionResponse::from)
    }

    @GetMapping("/api/roles")
    @RequiresPermission("SYSTEM.ROLE.VIEW")
    fun getAll(): List<RoleResponse> {
        accessService.current().requireOnPlatform("SYSTEM.ROLE.VIEW")
        return roleService.getAll().map(::response)
    }

    // For any user who can assign roles: which roles they may hand out in that society
    // (omit societyId for platform roles). Drives the role picker in the client.
    @GetMapping("/api/roles/assignable")
    fun assignable(@RequestParam(required = false) societyId: Long?): List<AssignableRoleResponse> {
        val access = accessService.current()
        return roleService.getAll()
            .filter { (if (societyId == null) it.scopeLevel == RoleScope.PLATFORM else it.scopeLevel == RoleScope.SOCIETY) }
            .filter { access.canGrant("USER.ROLE.ASSIGN", it.code, societyId) }
            .map { AssignableRoleResponse(it.code, it.name, it.scopeLevel, it.requiresFlat) }
    }

    @GetMapping("/api/roles/{code}")
    @RequiresPermission("SYSTEM.ROLE.VIEW")
    fun getByCode(@PathVariable code: String): RoleResponse {
        accessService.current().requireOnPlatform("SYSTEM.ROLE.VIEW")
        return response(roleService.getByCode(code))
    }

    @PostMapping("/api/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("SYSTEM.ROLE.CREATE")
    fun create(@Valid @RequestBody request: CreateRoleRequest): RoleResponse {
        accessService.current().requireOnPlatform("SYSTEM.ROLE.CREATE")
        val role = roleService.create(
            CreateRoleCommand(
                code = request.code,
                name = request.name,
                description = request.description,
                scopeLevel = request.scopeLevel,
                requiresFlat = request.requiresFlat,
                inherits = request.inherits,
                includedIn = request.includedIn,
                permissions = request.permissions,
            )
        )
        return response(roleService.getByCode(role.code))
    }

    @PatchMapping("/api/roles/{code}")
    @RequiresPermission("SYSTEM.ROLE.UPDATE")
    fun update(@PathVariable code: String, @RequestBody request: UpdateRoleRequest): RoleResponse {
        accessService.current().requireOnPlatform("SYSTEM.ROLE.UPDATE")
        roleService.update(code, request.name, request.description)
        return response(roleService.getByCode(code))
    }

    @PutMapping("/api/roles/{code}/permissions")
    @RequiresPermission("SYSTEM.PERMISSION.UPDATE")
    fun setPermissions(@PathVariable code: String, @RequestBody request: SetPermissionsRequest): RoleResponse {
        accessService.current().requireOnPlatform("SYSTEM.PERMISSION.UPDATE")
        roleService.setPermissions(code, request.permissions)
        return response(roleService.getByCode(code))
    }

    @PutMapping("/api/roles/{code}/inherits")
    @RequiresPermission("SYSTEM.ROLE.UPDATE")
    fun setInherits(@PathVariable code: String, @RequestBody request: SetInheritsRequest): RoleResponse {
        accessService.current().requireOnPlatform("SYSTEM.ROLE.UPDATE")
        roleService.setInherits(code, request.inherits)
        return response(roleService.getByCode(code))
    }

    @DeleteMapping("/api/roles/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission("SYSTEM.ROLE.DELETE")
    fun delete(@PathVariable code: String) {
        accessService.current().requireOnPlatform("SYSTEM.ROLE.DELETE")
        roleService.delete(code)
    }

    private fun response(role: Role) = roleService.describe(role.code) { r ->
        RoleResponse(
            code = r.code,
            name = r.name,
            description = r.description,
            scopeLevel = r.scopeLevel,
            requiresFlat = r.requiresFlat,
            allPermissions = r.allPermissions,
            isSystem = r.isSystem,
            inherits = r.inherits.map { it.code }.sorted(),
            permissions = r.permissions.map { it.code }.sorted(),
            effectivePermissions = permissionService.getPermissionsForRole(r.code).sorted(),
        )
    }
}
