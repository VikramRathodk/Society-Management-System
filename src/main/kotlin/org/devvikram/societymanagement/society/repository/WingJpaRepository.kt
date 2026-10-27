package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.Wing
import org.springframework.data.jpa.repository.JpaRepository

interface WingJpaRepository : JpaRepository<Wing, Long> {
    fun findBySocietyId(societyId: Long): List<Wing>
}
