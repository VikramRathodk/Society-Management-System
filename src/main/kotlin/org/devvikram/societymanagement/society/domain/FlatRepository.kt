package org.devvikram.societymanagement.society.domain

import java.util.UUID

interface FlatRepository {
    fun save(flat: Flat): Flat
    fun findById(id: UUID): Flat?
    fun findAll(): List<Flat>
    fun findByWingId(wingId: UUID): List<Flat>
    fun deleteById(id: UUID)
}
