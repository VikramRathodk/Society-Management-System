package org.devvikram.societymanagement.user.repository

import org.devvikram.societymanagement.user.model.User

interface UserRepository {
    fun save(user: User): User
    fun findById(id: Long): User?
    fun findAll(): List<User>
    fun findAllById(ids: Collection<Long>): List<User>
    fun findByPhone(phone: String): User?
    // Applied immediately in the database (its cascade removes role assignments), so a user that
    // history still references fails here, not at commit.
    fun delete(user: User)
}
