package org.devvikram.societymanagement.society.domain


interface WingRepository {
    fun save(wing: Wing): Wing
    fun findById(id: Long): Wing?
    fun findAll(): List<Wing>
    fun findBySocietyId(societyId: Long): List<Wing>
    fun deleteById(id: Long)
}
