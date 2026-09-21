package org.devvikram.societymanagement.user.domain

import java.util.UUID

interface AppUserRepository {
    fun save(appUser: AppUser): AppUser
    fun findById(id: UUID): AppUser?
    fun findAll(): List<AppUser>
    fun findByPhone(phone: String): AppUser?
}
