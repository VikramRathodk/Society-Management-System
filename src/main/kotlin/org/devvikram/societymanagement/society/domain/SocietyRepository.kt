package org.devvikram.societymanagement.society.domain

import java.util.UUID

interface SocietyRepository {
    fun save(society: Society): Society
    fun findById(id: UUID): Society?
    fun findAll(): List<Society>
}
