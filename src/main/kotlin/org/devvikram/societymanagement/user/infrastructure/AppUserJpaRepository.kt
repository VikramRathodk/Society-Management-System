package org.devvikram.societymanagement.user.infrastructure

import org.devvikram.societymanagement.user.domain.AppUser
import org.springframework.data.jpa.repository.JpaRepository

interface AppUserJpaRepository : JpaRepository<AppUser, Long> {
    fun findByPhone(phone: String): AppUser?
}
