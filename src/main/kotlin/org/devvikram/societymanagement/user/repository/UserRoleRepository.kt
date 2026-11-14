package org.devvikram.societymanagement.user.repository

import org.devvikram.societymanagement.user.model.UserRole

// Reads return assignments with role, society and flat already loaded, so callers can use them
// outside a transaction (open-in-view is off).
interface UserRoleRepository {
    fun save(userRole: UserRole): UserRole
    fun findById(id: Long): UserRole?
    fun findByUserId(userId: Long): List<UserRole>
    fun findByUserIds(userIds: Collection<Long>): List<UserRole>
    fun findUserIdsBySocietyIds(societyIds: Collection<Long>): Set<Long>
    fun findByFlatId(flatId: Long): List<UserRole>
    // People holding a role in the society: residents (onFlats) or society-wide (committee, guards, ...).
    fun countMembers(societyId: Long, onFlats: Boolean): Long
    fun delete(userRole: UserRole)
}
