package org.devvikram.societymanagement.authorization.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.authorization.dto.AssignableRoleResponse
import org.devvikram.societymanagement.authorization.dto.CreateRoleRequest
import org.devvikram.societymanagement.authorization.dto.PermissionResponse
import org.devvikram.societymanagement.authorization.dto.RoleResponse
import org.devvikram.societymanagement.authorization.dto.SetInheritsRequest
import org.devvikram.societymanagement.authorization.dto.SetPermissionsRequest
import org.devvikram.societymanagement.authorization.dto.UpdateRoleRequest
import org.devvikram.societymanagement.authorization.model.Role
import org.devvikram.societymanagement.authorization.model.RoleScope
import org.devvikram.societymanagement.authorization.service.CreateRoleCommand
import org.devvikram.societymanagement.authorization.service.PermissionService
import org.devvikram.societymanagement.authorization.service.RoleService
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

// Role definitions are platform-wide, so every write needs the permission at PLATFORM scope
// (i.e. a SUPER_ADMIN), not merely in some society.
@RestController
class RoleController(
    private val roleService: RoleService,
    private val permissionService: PermissionService,
    private val accessService: AccessService,
) {

    @GetMapping("/api/permissions")
    @RequiresPermission(Permissions.SYSTEM_PERMISSION_VIEW)
    fun permissions(): List<PermissionResponse> {
        accessService.current().requireOnPlatform(Permissions.SYSTEM_PERMISSION_VIEW)
        return roleService.getAllPermissions().map(PermissionResponse::from)
    }

    @GetMapping("/api/roles")
    @RequiresPermission(Permissions.SYSTEM_ROLE_VIEW)
    fun getAll(): List<RoleResponse> {
        accessService.current().requireOnPlatform(Permissions.SYSTEM_ROLE_VIEW)
        return roleService.getAll().map(::response)
    }

    // For any user who can assign roles: which roles they may hand out in that society
    // (omit societyId for platform roles). Drives the role picker in the client.
    @GetMapping("/api/roles/assignable")
    fun assignable(@RequestParam(required = false) societyId: Long?): List<AssignableRoleResponse> {
        val access = accessService.current()
        return roleService.getAll()
            .filter { (if (societyId == null) it.scopeLevel == RoleScope.PLATFORM else it.scopeLevel == RoleScope.SOCIETY) }
            .filter { access.canGrant(Permissions.USER_ROLE_ASSIGN, it.code, societyId) }
            .map { AssignableRoleResponse(it.code, it.name, it.scopeLevel, it.requiresFlat) }
    }

    @GetMapping("/api/roles/{code}")
    @RequiresPermission(Permissions.SYSTEM_ROLE_VIEW)
    fun getByCode(@PathVariable code: String): RoleResponse {
        accessService.current().requireOnPlatform(Permissions.SYSTEM_ROLE_VIEW)
        return response(roleService.getByCode(code))
    }

    @PostMapping("/api/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.SYSTEM_ROLE_CREATE)
    fun create(@Valid @RequestBody request: CreateRoleRequest): RoleResponse {
        accessService.current().requireOnPlatform(Permissions.SYSTEM_ROLE_CREATE)
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
    @RequiresPermission(Permissions.SYSTEM_ROLE_UPDATE)
    fun update(@PathVariable code: String, @RequestBody request: UpdateRoleRequest): RoleResponse {
        accessService.current().requireOnPlatform(Permissions.SYSTEM_ROLE_UPDATE)
        roleService.update(code, request.name, request.description)
        return response(roleService.getByCode(code))
    }

    @PutMapping("/api/roles/{code}/permissions")
    @RequiresPermission(Permissions.SYSTEM_PERMISSION_UPDATE)
    fun setPermissions(@PathVariable code: String, @RequestBody request: SetPermissionsRequest): RoleResponse {
        accessService.current().requireOnPlatform(Permissions.SYSTEM_PERMISSION_UPDATE)
        roleService.setPermissions(code, request.permissions)
        return response(roleService.getByCode(code))
    }

    @PutMapping("/api/roles/{code}/inherits")
    @RequiresPermission(Permissions.SYSTEM_ROLE_UPDATE)
    fun setInherits(@PathVariable code: String, @RequestBody request: SetInheritsRequest): RoleResponse {
        accessService.current().requireOnPlatform(Permissions.SYSTEM_ROLE_UPDATE)
        roleService.setInherits(code, request.inherits)
        return response(roleService.getByCode(code))
    }

    @DeleteMapping("/api/roles/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.SYSTEM_ROLE_DELETE)
    fun delete(@PathVariable code: String) {
        accessService.current().requireOnPlatform(Permissions.SYSTEM_ROLE_DELETE)
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
