package org.devvikram.societymanagement.security

import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Before
import org.devvikram.societymanagement.user.domain.Role
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

@Aspect
@Component
class PermissionAspect(private val permissionService: PermissionService) {

    @Before(value = "@annotation(requiresPermission)", argNames = "requiresPermission")
    fun check(requiresPermission: RequiresPermission) {
        val authentication = SecurityContextHolder.getContext().authentication
            ?: throw AccessDeniedException("Not authenticated")
        val roleName = authentication.authorities
            .mapNotNull { it.authority }
            .firstOrNull { it.startsWith("ROLE_") }
            ?.removePrefix("ROLE_")
            ?: throw AccessDeniedException("No role found for current user")
        if (!permissionService.hasPermission(Role.valueOf(roleName), requiresPermission.code)) {
            throw AccessDeniedException("Missing permission: ${requiresPermission.code}")
        }
    }
}
