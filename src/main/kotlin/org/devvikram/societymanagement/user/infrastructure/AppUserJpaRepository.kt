package org.devvikram.societymanagement.user.infrastructure

import org.devvikram.societymanagement.user.domain.AppUser
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AppUserJpaRepository : JpaRepository<AppUser, Long> {
    fun findByPhone(phone: String): AppUser?

    // Straight to SQL so the database's cascade (user_role) and foreign keys (history) decide,
    // not whatever the persistence context happens to hold.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AppUser u where u.id = :id")
    fun deleteDirectly(@Param("id") id: Long): Int
}
