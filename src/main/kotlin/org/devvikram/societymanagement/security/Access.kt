package org.devvikram.societymanagement.security

import org.springframework.security.access.AccessDeniedException

// What one user may do, resolved from their role assignments. Platform grants apply in every society.
class Access(
    val userId: Long,
    val grants: List<RoleGrant>,
    val platformPermissions: Set<String>,
    val societyPermissions: Map<Long, Set<String>>,
    private val platformSubordinates: Set<String>,
    private val societySubordinates: Map<Long, Set<String>>,
) {
    fun can(code: String, societyId: Long): Boolean =
        code in platformPermissions || code in societyPermissions[societyId].orEmpty()

    fun canOnPlatform(code: String): Boolean = code in platformPermissions

    fun canAnywhere(code: String): Boolean =
        canOnPlatform(code) || societyPermissions.values.any { code in it }

    // Societies where the permission holds; null means every society (a platform grant).
    fun societiesWith(code: String): Set<Long>? =
        if (canOnPlatform(code)) null else societyPermissions.filterValues { code in it }.keys

    // Higher roles may hand out only roles below them, and only where they hold them.
    fun outranks(roleCode: String, societyId: Long?): Boolean =
        roleCode in platformSubordinates ||
            (societyId != null && roleCode in societySubordinates[societyId].orEmpty())

    // Grant `roleCode` in `societyId` (null = platform): needs the permission there and a higher role there.
    fun canGrant(permission: String, roleCode: String, societyId: Long?): Boolean {
        val permitted = if (societyId == null) canOnPlatform(permission) else can(permission, societyId)
        return permitted && outranks(roleCode, societyId)
    }

    // Manage an existing user: allowed only if the actor has the permission and outranks
    // every role the target holds, in each scope the target holds it. A user without any
    // roles can only be managed platform-wide.
    fun canManage(permission: String, targetGrants: List<RoleGrant>): Boolean {
        if (targetGrants.isEmpty()) return canOnPlatform(permission)
        return targetGrants.all { canGrant(permission, it.roleCode, it.societyId) }
    }

    fun requireIn(code: String, societyId: Long) {
        if (!can(code, societyId)) throw AccessDeniedException("Missing permission: $code for this society")
    }

    fun requireOnPlatform(code: String) {
        if (!canOnPlatform(code)) throw AccessDeniedException("Missing platform permission: $code")
    }
}
