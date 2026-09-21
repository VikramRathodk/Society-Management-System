package org.devvikram.societymanagement.user.infrastructure

import org.devvikram.societymanagement.user.domain.AppUser
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AppUserJpaRepository : JpaRepository<AppUser, UUID> {
    fun findByPhone(phone: String): AppUser?
}
