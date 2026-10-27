package org.devvikram.societymanagement.authorization.repository

// Read-only view of role assignments (the user_role table). The user module owns that table and
// implements this port, so authorization never depends on the user module.
interface RoleAssignments {
    fun countByRoleId(roleId: Long): Long
}
