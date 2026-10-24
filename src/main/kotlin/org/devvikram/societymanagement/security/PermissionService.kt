package org.devvikram.societymanagement.security

import org.devvikram.societymanagement.security.permission.RolePermissionJpaRepository
import org.devvikram.societymanagement.user.domain.Role
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

// Cached per role. Any code path that writes role_permission must call evictAll() afterwards.
// Direct SQL edits to role_permission bypass that, so the cache also expires on a TTL
// (spring.cache.caffeine.spec in application.properties) as a safety net.
@Service
class PermissionService(private val rolePermissionRepository: RolePermissionJpaRepository) {

    @Cacheable("rolePermissions")
    fun getPermissionsForRole(role: Role): Set<String> = rolePermissionRepository.findPermissionCodesByRole(role)

    @CacheEvict("rolePermissions", allEntries = true)
    fun evictAll() = Unit

    // No hasPermission(role, code) helper here on purpose: calling getPermissionsForRole on `this`
    // skips the Spring proxy, so @Cacheable would silently never apply. Callers go through the bean.
}
