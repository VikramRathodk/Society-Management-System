package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.Flat
import org.springframework.data.jpa.repository.JpaRepository

interface FlatJpaRepository : JpaRepository<Flat, Long> {
    fun findByWingId(wingId: Long): List<Flat>
}
