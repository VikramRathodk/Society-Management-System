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
    // Split of the society grants: roles tied to a flat (RESIDENT-like) act only on that flat's
    // records, every other society role acts on the whole society.
    private val societyWidePermissions: Map<Long, Set<String>>,
    private val flatPermissions: Map<Long, Set<String>>,
) {
    fun can(code: String, societyId: Long): Boolean =
        code in platformPermissions || code in societyPermissions[societyId].orEmpty()

    fun canOnPlatform(code: String): Boolean = code in platformPermissions

    fun canAnywhere(code: String): Boolean =
        canOnPlatform(code) || societyPermissions.values.any { code in it }

    // Societies where the permission holds; null means every society (a platform grant).
    fun societiesWith(code: String): Set<Long>? =
        if (canOnPlatform(code)) null else societyPermissions.filterValues { code in it }.keys

    // For flat-owned records (visitors, later complaints): the permission across the whole society,
    // from a platform grant or a society role that is not tied to a flat.
    fun canSocietyWide(code: String, societyId: Long): Boolean =
        canOnPlatform(code) || code in societyWidePermissions[societyId].orEmpty()

    // Flats whose records the user may act on through a flat-tied role (e.g. a resident's own flat).
    fun flatsWith(code: String): Set<Long> = flatPermissions.filterValues { code in it }.keys

    fun canOnFlat(code: String, societyId: Long, flatId: Long): Boolean =
        canSocietyWide(code, societyId) || flatId in flatsWith(code)

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
