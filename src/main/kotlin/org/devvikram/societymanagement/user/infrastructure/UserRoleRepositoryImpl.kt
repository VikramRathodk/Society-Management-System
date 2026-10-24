package org.devvikram.societymanagement.user.infrastructure

import org.devvikram.societymanagement.user.domain.UserRole
import org.devvikram.societymanagement.user.domain.UserRoleRepository
import org.springframework.stereotype.Repository

@Repository
class UserRoleRepositoryImpl(
    private val userRoleJpaRepository: UserRoleJpaRepository,
) : UserRoleRepository {
    override fun save(userRole: UserRole): UserRole = userRoleJpaRepository.save(userRole)

    override fun findById(id: Long): UserRole? = userRoleJpaRepository.findWithDetailsById(id)

    override fun findByUserId(userId: Long): List<UserRole> = findByUserIds(listOf(userId))

    override fun findByUserIds(userIds: Collection<Long>): List<UserRole> =
        if (userIds.isEmpty()) emptyList() else userRoleJpaRepository.findWithDetailsByUserIds(userIds)

    override fun findUserIdsBySocietyIds(societyIds: Collection<Long>): Set<Long> =
        if (societyIds.isEmpty()) emptySet() else userRoleJpaRepository.findUserIdsBySocietyIds(societyIds)

    override fun countByRoleId(roleId: Long): Long = userRoleJpaRepository.countByRoleId(roleId)

    override fun delete(userRole: UserRole) = userRoleJpaRepository.delete(userRole)
}
