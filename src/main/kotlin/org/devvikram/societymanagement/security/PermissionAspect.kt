package org.devvikram.societymanagement.security

import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Before
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Component

// Coarse gate: the user must hold the permission in at least one scope. Endpoints that act on a
// specific society then check that society explicitly via Access.requireIn / canGrant / canManage.
@Aspect
@Component
class PermissionAspect(private val accessService: AccessService) {

    @Before(value = "@annotation(requiresPermission)", argNames = "requiresPermission")
    fun check(requiresPermission: RequiresPermission) {
        if (!accessService.current().canAnywhere(requiresPermission.code)) {
            throw AccessDeniedException("Missing permission: ${requiresPermission.code}")
        }
    }
}
