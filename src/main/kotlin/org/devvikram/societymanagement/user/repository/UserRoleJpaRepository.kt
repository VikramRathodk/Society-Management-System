package org.devvikram.societymanagement.user.repository

import org.devvikram.societymanagement.user.model.UserRole
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

    // Assignments tied to a flat (its residents), with the user loaded.
    @Query("select ur from UserRole ur join fetch ur.user where ur.flat.id = :flatId")
    fun findWithUserByFlatId(@Param("flatId") flatId: Long): List<UserRole>

    fun countByRoleId(roleId: Long): Long
}
