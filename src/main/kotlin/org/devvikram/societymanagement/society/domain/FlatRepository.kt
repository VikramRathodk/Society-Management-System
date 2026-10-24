package org.devvikram.societymanagement.society.domain


interface FlatRepository {
    fun save(flat: Flat): Flat
    fun findById(id: Long): Flat?
    fun findAll(): List<Flat>
    fun findByWingId(wingId: Long): List<Flat>
    fun deleteById(id: Long)
}
