package org.devvikram.societymanagement.user.domain

import java.util.UUID

// Reads return assignments with role, society and flat already loaded, so callers can use them
// outside a transaction (open-in-view is off).
interface UserRoleRepository {
    fun save(userRole: UserRole): UserRole
    fun findById(id: UUID): UserRole?
    fun findByUserId(userId: UUID): List<UserRole>
    fun findByUserIds(userIds: Collection<UUID>): List<UserRole>
    fun findUserIdsBySocietyIds(societyIds: Collection<UUID>): Set<UUID>
    fun countByRoleId(roleId: UUID): Long
    fun delete(userRole: UserRole)
}
