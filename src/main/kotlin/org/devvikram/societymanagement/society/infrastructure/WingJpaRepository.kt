package org.devvikram.societymanagement.society.infrastructure

import org.devvikram.societymanagement.society.domain.Wing
import org.springframework.data.jpa.repository.JpaRepository

interface WingJpaRepository : JpaRepository<Wing, Long> {
    fun findBySocietyId(societyId: Long): List<Wing>
}
