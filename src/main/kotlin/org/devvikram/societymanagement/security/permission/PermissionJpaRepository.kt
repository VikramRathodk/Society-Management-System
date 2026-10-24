package org.devvikram.societymanagement.security.permission

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface PermissionJpaRepository : JpaRepository<Permission, UUID> {
    @Query("select p.code from Permission p")
    fun findAllCodes(): Set<String>

    fun findByCodeIn(codes: Collection<String>): List<Permission>
}
