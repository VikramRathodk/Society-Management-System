package org.devvikram.societymanagement.society.domain


interface SocietyRepository {
    fun save(society: Society): Society
    fun findById(id: Long): Society?
    fun findAll(): List<Society>
    // Applied immediately in the database (its cascades remove wings, flats and role assignments), so a
    // society that history still references fails here, not at commit.
    fun delete(society: Society)
}
