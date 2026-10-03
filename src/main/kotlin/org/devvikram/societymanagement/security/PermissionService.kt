package org.devvikram.societymanagement.security

import org.devvikram.societymanagement.security.permission.RolePermissionJpaRepository
import org.devvikram.societymanagement.user.domain.Role
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

// Cached per role — evict the "rolePermissions" cache on any role_permission write once
// SYSTEM.PERMISSION.UPDATE is wired up (Phase 2, per society_planning.md).
@Service
class PermissionService(private val rolePermissionRepository: RolePermissionJpaRepository) {

    @Cacheable("rolePermissions")
    fun getPermissionsForRole(role: Role): Set<String> = rolePermissionRepository.findPermissionCodesByRole(role)

    fun hasPermission(role: Role, code: String): Boolean = getPermissionsForRole(role).contains(code)
}
