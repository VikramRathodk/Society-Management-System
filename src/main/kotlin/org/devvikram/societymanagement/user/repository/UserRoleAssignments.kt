package org.devvikram.societymanagement.user.repository

import org.devvikram.societymanagement.authorization.repository.RoleAssignments
import org.springframework.stereotype.Component

// The user module owns user_role, so it answers authorization's questions about assignments.
@Component
class UserRoleAssignments(
    private val userRoleJpaRepository: UserRoleJpaRepository,
) : RoleAssignments {
    override fun countByRoleId(roleId: Long): Long = userRoleJpaRepository.countByRoleId(roleId)
}
