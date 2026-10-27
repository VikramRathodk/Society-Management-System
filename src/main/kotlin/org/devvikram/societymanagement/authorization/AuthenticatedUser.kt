package org.devvikram.societymanagement.authorization

import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User

// One role assignment as the security layer sees it. societyId == null means platform-wide.
data class RoleGrant(
    val assignmentId: Long,
    val roleCode: String,
    val societyId: Long?,
    val flatId: Long?,
)

// The request's principal: the usual UserDetails plus the user's id and role assignments,
// loaded fresh on every request so role changes apply without re-login.
class AuthenticatedUser(
    val id: Long,
    phone: String,
    passwordHash: String,
    active: Boolean,
    val grants: List<RoleGrant>,
) : User(
    phone,
    passwordHash,
    active,
    true,
    true,
    true,
    grants.map { SimpleGrantedAuthority("ROLE_${it.roleCode}") }.distinct(),
)
