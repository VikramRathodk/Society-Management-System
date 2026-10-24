package org.devvikram.societymanagement.user.infrastructure

import org.devvikram.societymanagement.user.domain.UserRole
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserRoleJpaRepository : JpaRepository<UserRole, Long> {
    @Query(
        "select ur from UserRole ur join fetch ur.role left join fetch ur.society left join fetch ur.flat " +
            "where ur.id = :id"
    )
    fun findWithDetailsById(@Param("id") id: Long): UserRole?

    @Query(
        "select ur from UserRole ur join fetch ur.role left join fetch ur.society left join fetch ur.flat " +
            "where ur.user.id in :userIds"
    )
    fun findWithDetailsByUserIds(@Param("userIds") userIds: Collection<Long>): List<UserRole>

    @Query("select distinct ur.user.id from UserRole ur where ur.society.id in :societyIds")
    fun findUserIdsBySocietyIds(@Param("societyIds") societyIds: Collection<Long>): Set<Long>

    fun countByRoleId(roleId: Long): Long
}
