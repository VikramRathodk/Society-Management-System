package org.devvikram.societymanagement.user.domain


interface AppUserRepository {
    fun save(appUser: AppUser): AppUser
    fun findById(id: Long): AppUser?
    fun findAll(): List<AppUser>
    fun findAllById(ids: Collection<Long>): List<AppUser>
    fun findByPhone(phone: String): AppUser?
    // Applied immediately in the database (its cascade removes role assignments), so a user that
    // history still references fails here, not at commit.
    fun delete(appUser: AppUser)
}
