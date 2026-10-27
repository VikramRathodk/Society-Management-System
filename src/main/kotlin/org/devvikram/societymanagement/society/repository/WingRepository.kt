package org.devvikram.societymanagement.society.repository

import org.devvikram.societymanagement.society.model.Wing

interface WingRepository {
    fun save(wing: Wing): Wing
    fun findById(id: Long): Wing?
    fun findAll(): List<Wing>
    fun findBySocietyId(societyId: Long): List<Wing>
    fun deleteById(id: Long)
}
