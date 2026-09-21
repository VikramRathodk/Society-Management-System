package org.devvikram.societymanagement.user.infrastructure

import org.devvikram.societymanagement.user.domain.AppUser
import org.devvikram.societymanagement.user.domain.AppUserRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AppUserRepositoryImpl(
    private val appUserJpaRepository: AppUserJpaRepository,
) : AppUserRepository {
    override fun save(appUser: AppUser): AppUser = appUserJpaRepository.save(appUser)

    override fun findById(id: UUID): AppUser? = appUserJpaRepository.findById(id).orElse(null)

    override fun findAll(): List<AppUser> = appUserJpaRepository.findAll()

    override fun findByPhone(phone: String): AppUser? = appUserJpaRepository.findByPhone(phone)
}
