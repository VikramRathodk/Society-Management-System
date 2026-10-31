package org.devvikram.societymanagement.user.repository

import org.devvikram.societymanagement.user.model.User

interface UserRepository {
    fun save(user: User): User
    fun findById(id: Long): User?
    fun findAll(): List<User>
    fun findAllById(ids: Collection<Long>): List<User>
    fun findByPhone(phone: String): User?
    // The in-memory test fakes rely on this default; the JPA adapter counts in the database.
    fun count(): Long = findAll().size.toLong()
    // Forgets these push tokens on every user except exceptUserId; returns how many users changed.
    fun clearFcmTokens(tokens: Collection<String>, exceptUserId: Long? = null): Int
    // Applied immediately in the database (its cascade removes role assignments), so a user that
    // history still references fails here, not at commit.
    fun delete(user: User)
}
