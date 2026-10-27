package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.Society
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SocietyJpaRepository : JpaRepository<Society, Long> {
    // Straight to SQL so the database's cascades and foreign keys decide, not whatever the persistence
    // context happens to hold; clearAutomatically drops the now-stale wings, flats and roles.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Society s where s.id = :id")
    fun deleteDirectly(@Param("id") id: Long): Int
}
