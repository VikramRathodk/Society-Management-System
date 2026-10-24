package org.devvikram.societymanagement.security

import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

@Service
class AccessService(private val permissionService: PermissionService) {

    fun current(): Access {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedUser
            ?: throw AccessDeniedException("Not authenticated")
        return accessFor(principal.id, principal.grants)
    }

    fun accessFor(userId: Long, grants: List<RoleGrant>): Access {
        val (platformGrants, societyGrants) = grants.partition { it.societyId == null }
        val bySociety = societyGrants.groupBy { it.societyId!! }
        return Access(
            userId = userId,
            grants = grants,
            platformPermissions = platformGrants.flatMap { permissionService.getPermissionsForRole(it.roleCode) }.toSet(),
            societyPermissions = bySociety.mapValues { (_, g) ->
                g.flatMap { permissionService.getPermissionsForRole(it.roleCode) }.toSet()
            },
            platformSubordinates = platformGrants.flatMap { permissionService.getSubordinateRoles(it.roleCode) }.toSet(),
            societySubordinates = bySociety.mapValues { (_, g) ->
                g.flatMap { permissionService.getSubordinateRoles(it.roleCode) }.toSet()
            },
        )
    }
}
