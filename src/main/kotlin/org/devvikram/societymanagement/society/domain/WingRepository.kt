package org.devvikram.societymanagement.society.domain

import java.util.UUID

interface WingRepository {
    fun save(wing: Wing): Wing
    fun findById(id: UUID): Wing?
    fun findAll(): List<Wing>
    fun findBySocietyId(societyId: UUID): List<Wing>
    fun deleteById(id: UUID)
}
