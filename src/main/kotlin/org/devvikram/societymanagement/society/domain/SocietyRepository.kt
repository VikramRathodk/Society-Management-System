package org.devvikram.societymanagement.society.domain


interface SocietyRepository {
    fun save(society: Society): Society
    fun findById(id: Long): Society?
    fun findAll(): List<Society>
}
