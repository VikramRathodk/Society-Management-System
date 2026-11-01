package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.Flat
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FlatJpaRepository : JpaRepository<Flat, Long> {
    fun findByWingId(wingId: Long): List<Flat>

    @Query("select f from Flat f join fetch f.wing w where w.society.id = :societyId order by w.name, f.flatNumber")
    fun findBySocietyId(@Param("societyId") societyId: Long): List<Flat>
}
