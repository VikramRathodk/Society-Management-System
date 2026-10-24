package org.devvikram.societymanagement.user.domain


interface AppUserRepository {
    fun save(appUser: AppUser): AppUser
    fun findById(id: Long): AppUser?
    fun findAll(): List<AppUser>
    fun findAllById(ids: Collection<Long>): List<AppUser>
    fun findByPhone(phone: String): AppUser?
}
