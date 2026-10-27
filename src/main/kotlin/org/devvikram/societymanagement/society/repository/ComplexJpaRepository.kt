package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.Complex
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ComplexJpaRepository : JpaRepository<Complex, Long> {
    // Straight to SQL so the foreign key from society decides (see SocietyJpaRepository.deleteDirectly).
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Complex c where c.id = :id")
    fun deleteDirectly(@Param("id") id: Long): Int
}
