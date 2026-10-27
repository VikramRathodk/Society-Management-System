package org.devvikram.societymanagement.user.repository

import org.devvikram.societymanagement.user.model.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserJpaRepository : JpaRepository<User, Long> {
    fun findByPhone(phone: String): User?

    // Straight to SQL so the database's cascade (user_role) and foreign keys (history) decide,
    // not whatever the persistence context happens to hold.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from User u where u.id = :id")
    fun deleteDirectly(@Param("id") id: Long): Int
}
