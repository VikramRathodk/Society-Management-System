package org.devvikram.societymanagement.user.repository

import org.devvikram.societymanagement.user.model.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserJpaRepository : JpaRepository<User, Long> {
    fun findByPhone(phone: String): User?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.fcmToken = null where u.fcmToken in :tokens")
    fun clearFcmTokens(@Param("tokens") tokens: Collection<String>): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.fcmToken = null where u.fcmToken in :tokens and u.id <> :exceptId")
    fun clearFcmTokensExcept(@Param("tokens") tokens: Collection<String>, @Param("exceptId") exceptId: Long): Int

    // Straight to SQL so the database's cascade (user_role) and foreign keys (history) decide,
    // not whatever the persistence context happens to hold.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from User u where u.id = :id")
    fun deleteDirectly(@Param("id") id: Long): Int
}
