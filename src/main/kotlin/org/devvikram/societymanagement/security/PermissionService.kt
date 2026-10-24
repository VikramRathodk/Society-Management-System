package org.devvikram.societymanagement.security

import org.devvikram.societymanagement.security.permission.PermissionJpaRepository
import org.devvikram.societymanagement.security.role.RoleRepository
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// Resolves the role hierarchy, cached per role code. Every code path that writes roles,
// role_permission or role_inheritance must call evictAll() afterwards (RoleService does).
// Direct SQL edits bypass that, so the caches also expire on a TTL
// (spring.cache.caffeine.spec in application.properties) as a safety net.
@Service
class PermissionService(
    private val roleRepository: RoleRepository,
    private val permissionRepository: PermissionJpaRepository,
) {

    // Effective permissions: the role's own grants plus those of every role it inherits.
    @Cacheable("rolePermissions")
    @Transactional(readOnly = true)
    fun getPermissionsForRole(roleCode: String): Set<String> {
        val roles = roleRepository.findByCode(roleCode)?.selfAndInherited() ?: return emptySet()
        if (roles.any { it.allPermissions }) return permissionRepository.findAllCodes()
        return roles.flatMap { role -> role.permissions.map { it.code } }.toSet()
    }

    // Roles strictly below this one — the roles its holder may assign and manage.
    // An all-permissions role sits above every role except other all-permissions roles.
    @Cacheable("roleSubordinates")
    @Transactional(readOnly = true)
    fun getSubordinateRoles(roleCode: String): Set<String> {
        val role = roleRepository.findByCode(roleCode) ?: return emptySet()
        if (role.allPermissions) {
            return roleRepository.findAll().filterNot { it.allPermissions }.map { it.code }.toSet()
        }
        return (role.selfAndInherited() - role).map { it.code }.toSet()
    }

    @CacheEvict(cacheNames = ["rolePermissions", "roleSubordinates"], allEntries = true)
    fun evictAll() = Unit

    // No hasPermission(role, code) helper here on purpose: calling a cached method on `this`
    // skips the Spring proxy, so @Cacheable would silently never apply. Callers go through the bean.
}
