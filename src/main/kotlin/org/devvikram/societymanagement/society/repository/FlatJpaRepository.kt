package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.Flat
import org.springframework.data.jpa.repository.JpaRepository

interface FlatJpaRepository : JpaRepository<Flat, Long> {
    fun findByWingId(wingId: Long): List<Flat>
}
